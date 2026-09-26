# Rent-auction submission package

Author: **arkadianet**. Status: **draft EIP plus executable reference implementation**.
No activation height is assigned. This package is for technical submission and
private-network evaluation, not a claim of independently audited deployment readiness.

Start with [EIP-XXXX.md](EIP-XXXX.md). It describes the proposed consensus rules.
[OPERATIONS.md](OPERATIONS.md) explains how to build and operate the tooling;
[VERIFICATION.md](VERIFICATION.md) records exactly what was tested;
[REVIEW.md](REVIEW.md) lists protocol decisions that reviewers must resolve.

## What is included

| Component | Location from repository root |
| --- | --- |
| Auction and reserve-deposit ErgoScript | `ergo-core/src/main/resources/rent-auction/` |
| Compiled contracts, consensus rules and builders | `ergo-core/src/main/scala/org/ergoplatform/modifiers/mempool/rentauction/` |
| UTXO/digest block enforcement | `src/main/scala/org/ergoplatform/nodeView/state/ErgoState.scala` |
| Mempool/candidate checks | `UtxoStateReader.scala`, `mining/CandidateGenerator.scala` |
| Wallet signing support | `ergo-wallet/.../ErgoProvingInterpreter.scala`, `ErgoWalletActor.scala` |
| Offline JSON builder/contract manifest | `src/main/scala/org/ergoplatform/tools/RentAuctionCli.scala` |
| Operator CLI and SQLite indexer | `tools/rent-auction/rent_auction.py` |
| Lithos candidate-source adapter | `tools/rent-auction/lithos/` |
| Scala tests | core and root `src/test/` rent-auction suites |
| Python tests, verification/package scripts | `tools/rent-auction/` |
| Compiled scripts and transaction examples | `docs/rent-auction/vectors/` |

## Plain-language money flow

1. A miner collects eligible rent. Ordinary rent ERG pays its committed beneficiary.
   Fully consumed transferable tokens enter auction lots; the miner separately
   supplies the ERG needed to support those lots.
2. Buyers bid. Replacing a bid refunds the former bidder in full.
3. A closer sends the tokens to the winning recipient and places the winning ERG
   in a dedicated deposit. An unsold lot instead burns its tokens.
4. A merger consumes that deposit and increases the existing re-emission reserve.

The last two steps can be separate transactions in one block. The existing deposit
contract is already a two-step arrangement: deposit, then a transaction involving
the reserve. Its rules also allow a deposit to accompany a scheduled reward payout
without increasing the reserve. The new covenant removes that alternative **for
these auction proceeds** by requiring an exact increase of the reserve principal.
It leaves ordinary legacy deposits and the existing reserve contract in place.

The extra reserve balance follows the native emission rules. It does not raise the
scheduled reward rate; it can support additional rewards later while the reserve
remains available. Funds waiting in a deposit have not yet increased that reserve.

## Submission files

The packaging script writes `dist/rent-auction-submission.zip`, a node patch against
the pinned baseline, an optional assembled JAR and SHA-256 checksums. The source
archive excludes local wallet data, node databases, dependency caches and build logs.
Apply the node patch to the exact baseline; apply the separate Lithos patch to its
documented revision. Review both before building.

Suggested submission title: **Draft: producer-attested storage-rent auctions funding
the EIP-27 reserve**. The EIP number should be assigned by the EIP maintainers.
Publishing this package for review does not activate or deploy the proposal.
Prepared submission titles and descriptions are in [SUBMISSION.md](SUBMISSION.md).
