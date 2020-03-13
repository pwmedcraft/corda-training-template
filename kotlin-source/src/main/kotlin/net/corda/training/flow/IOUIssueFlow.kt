package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val issueCommand = Command(IOUContract.Commands.Issue(), state.participants.map{it.owningKey})
        val stateAndContract = StateAndContract(state, IOUContract.IOU_CONTRACT_ID)
        val notary = serviceHub.networkMapCache.notaryIdentities.single()
        val transactionBuilder = TransactionBuilder(notary).withItems(issueCommand, stateAndContract)
        transactionBuilder.verify(serviceHub)
        val partiallySignedTx: SignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
        val requiredSigners: List<Party> = state.participants.filterNot{it == ourIdentity}
        val cannotInlineThis = requiredSigners.map{initiateFlow(it)}
        val signedTransaction = subFlow(CollectSignaturesFlow(partiallySignedTx, cannotInlineThis.toSet()))
        return subFlow(FinalityFlow(signedTransaction, cannotInlineThis))
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputsOfType<IOUState>().single()
                "This must be an IOU transaction" using (output is IOUState)
                "Lender cannot be borrower" using (output.lender != output.borrower)
            }
        }
        val signedTx = subFlow(signedTransactionFlow)
        subFlow(ReceiveFinalityFlow(flowSession, signedTx.id))
    }
}