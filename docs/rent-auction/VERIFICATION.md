# Verification record

The machine-readable [verification.json](verification.json) is authoritative for a
particular verified build. Regenerate it with:

```
python tools/rent-auction/verify.py --assemble --lithos PATH
```

It records the run date, selected commands, pass counts and assembled JAR SHA-256.
Full logs go to `target/rent-auction-verification/`. The counts and identities below
were supplied by the reviewer from the last clean runs outside the sandbox. They
are not a claim that the checked-in report has already been regenerated to match
this documentation revision. Reconcile any mismatch after the final verifier run.

## Reproduction

Use JDK 11, the pinned node checkout, Python 3.10+, and the pinned patched Lithos
checkout in [the adapter guide](../../tools/rent-auction/lithos/README.md). Run from
the node repository root. A test failure stops verification before a fresh success
report is written. The selected root suites run with `Test / parallelExecution := false`.

Before sbt, the verifier deletes `target/rent-auction-vectors/`. `RentAuctionCliSpec`
must recreate `collect-request.json` and `collect-plan.json`; a missing file is an
error even if sbt exits successfully. With assembly enabled, the verifier generates
the mainnet manifest and requires the standalone JAR's plan to equal the Scala
vector. It also checks that the source fingerprint did not change during the run.

## Selected suites

| Component | Selected result | Scope |
| --- | ---: | --- |
| ergoCore rent-auction + `ReemissionRulesSpec` | 44 passed; 1 pre-existing ignored | Baseline, contracts, rules, builders, serializer envelopes |
| Root rent-auction + `ExpirationSpecification` | 50 passed | Activated execution, state, CLI vectors and economics |
| `ErgoProvingInterpreterSpec` | 4 passed | Existing signing regressions |
| Candidate and wallet-service suites | 47 passed | `CandidateGeneratorSpec`, `CandidateGeneratorPropSpec`, `ErgoWalletServiceSpec` |
| Python operator suites | 31 passed | Index, batching, stale rebuilds and HTTP transport |
| Patched Lithos rent suites | 41 passed | 6 adapter tests and 35 existing rent tests |

Total in `verification.json`: **217 passed, 1 pre-existing ignored**. The command
set is in `verify.py`; the machine-readable report remains the run authority.
This selection does not cover the entire repository matrix, Docker integration
suites or mainnet bootstrap/replay. Expected-negative cases may log validation
errors; suite outcomes determine success.

`CandidateGeneratorSpec` includes upstream Akka timing tests with 9-second
`expectMsg` waits. The reviewer observed one timeout at load average 37; it passed
on rerun. A heavily loaded machine can hit this timing limit. Record any failure
and rerun result rather than treating an incomplete gate as a success.

## Contract identity

The reviewer obtained these values from the assembled JAR's `manifest mainnet`,
using the implemented constants and sigma-state 6.0.6:

| Contract | Serialized bytes | Blake2b-256 |
| --- | ---: | --- |
| Auction | 991 | `3643df8a7e682486e5ffa7b3a113a65b18c3f65320856c3e32616afaf2c8ce09` |
| Proceeds deposit | 816 | `1cb94a993884ef836418ee714efa8169d23adc2bdca8a2dcd8c71c57f91ba845` |

`RentAuctionContractSpec` checks compilation of version-0 trees. The deposit is
compiled first, and the auction embeds its hash. The node still checks the exact
deposit tree. Full trees, fee/reserve scripts and NFT belong in the regenerated
[manifest](vectors/mainnet-contracts.json). Changes to constants or compiler
versions require reviewing new identities and regenerating vectors.

## What the tests establish

Consensus evidence uses native `ErgoTransaction.statefulValidity` and, for the new
restrictions, activated `ErgoState.execTransactions`. In `RentAuctionV2Spec`, the
acceptance helper checks both paths and cost accounting. Its rejection helper checks
the specific expected reason. A test calling only `RentAuctionRules.validate` is
supporting evidence, not proof of full transaction or block validity.

* **Baseline behavior:** `RentAuctionBaselineSpec` covers funded recreation aliasing,
  malformed Short witnesses, inclusive age, owner spending and both wrapped-Int fee
  regions. `ExpirationSpecification` supplies the existing rent regressions.
* **Opening and bundling:** `RentAuctionV2Spec` covers same-ID aggregation, explicit
  partitions, dense sources, exact commitments, source requirements, seed minimum,
  token-entry cap and token-free funding/change. It excludes funded recreations
  from fresh lots and from the beneficiary payment.
* **EIP-27 and protected state:** `RentAuctionV2Spec` checks accounting-token burns
  with separate native payment and rejects rent bypass through well-formed auction,
  deposit and authentic reserve boxes. Malformed imitations retain the normal path.
* **Bids:** `RentAuctionContractSpec` and `RentAuctionV2Spec` cover full refunds,
  preserved lot state, minimum/increment rejection, recipient parsing, bounded
  deadlines and refund creation height. `RentAuctionParameterSpec` covers Int limits.
* **Batch close:** `RentAuctionV2Spec` covers collector returns, sale payouts, burns,
  mixed same-token lots, disjoint output ranges, deterministic fees, authenticated
  byte price, exact tags/heights and the 32-lot limit. It rejects extra token escape
  and shared obligations even when native validation alone would allow them.
* **Dust and envelopes:** `RentAuctionParameterSpec` serializes maximum-width return,
  winner, deposit, successor and fee boxes. `RentAuctionV2Spec` executes lifecycles at
  byte prices 360 and 10,000, including a vote after opening, invalid 0.001 ERG fees,
  and singleton merges needing sponsorship at the high price.
* **Reserve accounting:** `RentAuctionDepositSpec` and `RentAuctionV2Spec` cover exact
  principal increases, multiple deposits, authentic reserve selection, fee limits,
  attempted reward sweeps and double counting. They preserve native reserve execution.
* **Block ordering:** `RentAuctionBlockSpec` executes collection followed by bids,
  close followed by merge, and scheduled reserve reward followed by merge. It also
  tests no-bid state removal, missing commitment, bad order and double spending.
* **Persistent integration:** `RentAuctionStateSpec` applies collection to UTXO and
  digest state, rejects missing attestation, rolls back and reapplies. It checks
  mempool rejection, producer candidate admission, beneficiary matching and cost limits.
* **Builders and vectors:** `RentAuctionBuilderSpec` checks lifecycle builders and
  wallet signing with an empty rent proof. `RentAuctionCliSpec` checks schema-2
  plans, context variables and token quantities, and generates fixed four-source,
  two-lot vectors with a P2PK collector. The standalone comparison is a verifier gate.
* **Economics and timing:** `RentAuctionEconomicsSpec` checks 1/20/32-source lifecycle
  outcomes at both byte prices through native and activated validation. It measures
  the block-cost boundary, a 32-lot sale close and similarly sized plain controls.
  Results and assumptions are in [ECONOMICS.md](ECONOMICS.md) and [REVIEW.md](REVIEW.md).
* **Operator transport:** `test_rent_auction.py` covers rollback, network isolation,
  batching, preserved extensions, stale rebuilds, unfunded merges and ambiguous
  submission failures. `test_operator_http.py` checks live request replacement,
  signing/candidate payloads, height-bound queues and check-before-broadcast.
* **Lithos transport:** patched `AuctionRentSourceSpec` checks schema-2 queue admission,
  exact JSON preservation, repeated serialized Short witnesses, R9, stale height,
  budgets and malformed entries. Its fixture comes from native- and activated-
  validated collection output. `StorageRentSourceSpec` and `StorageRentBuilderSpec`
  provide the existing regressions. These tests do not replace node validation.

## Limits

Fixtures use synthetic boxes and fake PoW, not live funds. Persistent state is tested;
complete networking, history and mainnet replay are not part of this selection.
Existing history rules authenticate block sections. HTTP tests use a local mock
server, and Lithos testing is not a live collateralized mining session.

The measurements do not establish token values, participation, profitability for
all sources or a worst-case CPU bound. The close-cost calibration concern is open.
No count constitutes independent audit or coordinated activation. Activation stays
unset. The full verifier, regenerated artifacts and external protocol review are
required before treating this draft as a deployment candidate.
