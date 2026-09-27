# Review notes

## Decisions deliberately left to protocol review

* Assign the EIP number, extension keys and activation mechanism/height. Reconcile
  provisional `0x0300` with EIP-0052. Activation remains unset.
* Ratify the 0.05 ERG minimum bid. It funds payouts at the byte-price ceiling but
  excludes lower bids, including the 0.005–0.05 ERG range discussed in
  [ECONOMICS.md](ECONOMICS.md). No bid is not proof that the assets are worthless.
* Ratify the 10% collector share. It rewards sale discovery and asset selection,
  but gives a self-bidding collector about an 11.1% gross-bid advantage for the same
  net outlay, before rounding, fees and carrier differences.
* Ratify the 0.002 ERG close allowance, equal total close-fee ceiling, refundable
  seed floor, 32-lot close limit, windows and increment. These are the draft values
  in `RentAuctionContracts.scala`, not an economic optimum.
* Review cleanup incentives. Close pays only the native fee, not a private bounty.
  Collectors want their seed back and winners want their tokens, but neither expiry
  nor consensus guarantees a submitted close. Unclosed protocol boxes remain live.
* Treat reserve merging after mainnet height 2,080,800 as a producer task. The
  scheduled reward spends the previous reserve; a public merge conflicts with it.
  Merge into the reward transaction's successor. The merge budget remains a native
  miner fee, with exactly two outputs and no keeper payment.
* Account for fee dust. The measured 147-byte fee box needs 0.00147 ERG at byte price
  10,000. A 0.001 ERG fee is invalid there. A singleton 0.001 ERG merge budget needs
  another deposit or a sponsor; seed sizing does not fix fee-output dust.
* Calibrate the provisional validation cost against adversarial layouts, especially
  batch closes. The measurements below identify a remaining imbalance; coefficients
  are unchanged.
* Review combined activation with EIP-48 and other rent proposals. Wrapped `Int`
  rent arithmetic and the EIP-27 accounting-token exception remain unchanged.

## Cost calibration

The reviewer measured `RentAuctionEconomicsSpec` outside the sandbox at byte price
360: medians of 31 samples after 10 warmups. Each sample decodes a fresh transaction
and runs native validation plus `RentAuctionRules.validate`. Acceptance is also
checked through activated `ErgoState.execTransactions`. These are local timing
measurements, not worst-case bounds or a mainnet capacity forecast.

| Transaction | Bytes | Native + extra cost | Median ms | Cost units/ms |
| --- | ---: | ---: | ---: | ---: |
| Collection: 259 rent sources + 1 funding input, 9 lots | 29,603 | 647,751 + 352,156 | 8.55 | 116,943 |
| Plain transfer of the collection shape | 28,835 | 635,060 + 287,184 | 7.71 | 119,677 |
| Close: 32 sold one-token lots | 41,472 | 103,260 + 574,804 | 13.69 | 49,524 |
| Plain transfer of the close shape | 41,110 | 90,332 + 212,052 | 1.57 | 192,790 |

The collection has 260 total inputs. `collectionSources` in the spec excludes its
funding input; adding the next rent source exceeds the 1,000,000 test cost limit.
Plain controls replace contract bytes with register padding to keep sizes similar.
The figures above use the reviewer's timing run, not sandbox timings.

Collections are charged roughly in line with plain transfers in this comparison.
The 32-lot close receives about 3.9 times fewer cost units per millisecond than its
plain control. This is a calibration item before activation. The current formula
in `RentAuctionRules.cost` remains provisional; this review assigns no new values.

## Evidence versus deployment claims

The package implements the path in the reference Scala node, including UTXO and
digest processing. `RentAuctionV2Spec` checks native acceptance or a specific native
rejection, then activated block-executor acceptance or the expected rejection reason.
Rule-only unit tests are supporting evidence, not independent consensus proof.

Tests start from synthetic snapshots and use the repository's fake PoW scheme.
They do not replay mainnet, coordinate live activation or provide an independent
audit. Patched Lithos tests exercise queue transport and admission plus existing
rent regressions. They do not qualify a live pool, Stratum or collateral lender.
See [VERIFICATION.md](VERIFICATION.md) for named suites and reviewer-reported counts.

## Deliberate limits of the operator implementation

* The CLI uses schema 2. Funding UTXOs, collector, beneficiary and recipient scripts
  are explicit. There is no graphical bidding site, coin selector or auto-bidding.
* The SQLite index starts at block 1 and retains confirmed boxes and rollback data.
  Initial sync requires an archival node. There is no snapshot import or bounded-history pruning.
* Workers run one pass; an external scheduler may repeat it. Worker submission
  requires `--execute`. Close batches are sorted and reduced by conservative size
  and cost admission; the node checks actual validity before broadcast.
* Workers rebuild stale batches and report spent inputs, deferred merges and dust-
  unfunded remainders. They do not retry an ambiguous submission automatically.
* The Lithos queue carries one funded schema-2 collection for an exact height.
  It preserves serialized extensions and the lender's header key. It transports
  lots and R9; the node validates them. Rent does not enter Lithos holding accounting.
* Candidate submission is not inclusion. Inspect the resulting candidate and chain.
  Conflicts or resource limits may exclude a transaction.
* Neither the generic merge worker nor the Lithos source rebases a public merge
  onto a producer's private reward transaction. That integration is still needed
  for unattended merging after scheduled re-emission starts.

## Particularly important regressions

* `RentAuctionBaselineSpec` preserves the baseline funded-recreation alias case,
  Short witness behavior, inclusive rent age and both `Int` wrap regions.
* `RentAuctionV2Spec` requires distinct funded recreation indices but permits repeated
  fully consumed witnesses. It checks aggregation, explicit partitions, dense-source
  splitting, commitments, no sourceless lots and token-free funding/change.
* The same root spec excludes recreations from fresh-lot recognition and beneficiary
  credit. It keeps EIP-27 accounting-token burns and their separate native payment.
* It tests disjoint mixed sold/unsold payouts, same-token lots, deterministic fees,
  authenticated byte price, pinned payout heights, the close limit and blocked token
  escape. Collector principal returns independently of the closer or a sale.
* `RentAuctionParameterSpec` checks serializer envelopes; `RentAuctionV2Spec` runs
  opening, bid, refund, burn, sale and merge at both byte prices, including a vote
  after opening. High-price singleton merges need a sponsor; two budgets suffice.
* `RentAuctionDepositSpec` and `RentAuctionV2Spec` reject reserve substitution,
  principal diversion and double counting. Legacy deposits retain their old reward
  path; auction deposits must strictly increase reserve principal.
* `RentAuctionBlockSpec` exercises collection/bids and close/merge in one block,
  and reward withdrawal before merge. `RentAuctionStateSpec` tests persistent UTXO
  and digest application, rollback/reapply, and rent admission through candidates.
* `RentAuctionCliSpec` regenerates schema-2 bundled vectors. `verify.py` removes stale
  vectors before sbt and stops if either collection file is missing afterward.

## Provenance

* Node baseline: `5528ef569a41ebccbc8658212e6ee3c97d990b96`.
* Lithos baseline: `88bb1822022bf9521c281314c060a8943521d0f6`.
* Scala 2.12.20, sigma-state 6.0.6.
* Implemented revisions: `e521dba59` (core), `385e68df2` (tooling and measurements),
  `6b16deed7` (vector regeneration).
* EIP-0051 PR #108, EIP-0052 PR #109 and node PR #2577 are proposal references,
  not assumed activated rules. Economic comparison provenance is in [ECONOMICS.md](ECONOMICS.md).
