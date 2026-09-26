# Review notes

## Decisions deliberately left to protocol review

* Assign the EIP number, extension keys and network activation mechanism/height.
  Reconcile the provisional `0x0300` field with EIP-0052 before either is deployed.
* Ratify the window, bid increment, seed, carrier and merging budgets. Current
  constants are executable draft choices, not claims about optimal economics.
* Benchmark the additional cost schedule under adversarial transaction layouts.
  Tests check its accounting and limits, not a universal CPU upper bound.
* Review the active proposal alongside EIP-48. The baseline's wrapped fee arithmetic
  is retained; adopting EIP-48 later requires new combined vectors and review.
* Review miner and keeper incentives. A correct auction can still be unprofitable
  to create, settle or merge. No auction runs itself and no deadline forces inclusion.
* Assess censorship and no-bid burns. The market opportunity is bounded and does
  not establish objective asset worth. Tokens may still be valuable when burned.

## Evidence versus deployment claims

The package implements the whole proposed path in the reference Scala node and
provides builders and operational commands. This is not merely a sigma-rust SDK
simulation. Persistent state tests include both UTXO and digest processing.

Tests start from synthetic snapshots and use the repository's fake PoW scheme.
They do not replay the entire mainnet chain, coordinate an activation across a
live network, test a real mining pool, or provide an independent security audit.
The Lithos tests compile the adapter inside the pinned client and exercise queue
admission plus existing rent regressions; they do not constitute a live Stratum
and collateral-lender deployment.

## Deliberate limits of the first operator implementation

* CLI and JSON workflows are provided. There is no graphical bidding website.
* The indexer starts at block 1 and keeps confirmed live boxes plus rollback data.
  It requires an archival node for its initial scan, can be expensive on mainnet,
  and has no trusted snapshot importer or bounded-history pruning mode.
* Worker commands run one pass. An operator may schedule them externally; this
  package installs no background service and submits nothing without `--execute`.
* Funding UTXOs and recipient scripts are explicit. There is no automatic wallet
  coin selector, DEX price feed, asset whitelist or auto-bidding strategy.
* The Lithos queue carries one pre-funded collection batch for an exact height.
  Rebuild it when the tip changes. Rent goes directly to the configured beneficiary;
  the adapter does not feed it into Lithos's holding top-up/capital accounting.
* The node checks candidate validity. A returned candidate may omit a transaction
  because it conflicts or exceeds limits. Inspect its inclusion proof; submission
  is not evidence of inclusion or confirmation.
* After re-emission starts, reserve withdrawal must precede a same-block merge,
  using that withdrawal's reserve successor. The block tests cover this order;
  neither the generic keeper nor this Lithos rent-source adapter automatically
  rebases a public merge against a miner's private reward transaction. That pool
  integration is still needed for unattended merging alongside scheduled rewards.

## Particularly important regressions

The old interpreter can accept two rent inputs naming one recreated output. The
proposal requires unique obligation outputs. Persistence testing also caught and
fixed an array-identity mistake in input ID comparisons: IDs are compared by bytes.

The legacy EIP-27 deposit can accompany reward withdrawal without increasing the
reserve. The new deposit requires a positive, exact increase covering every merged
principal. Existing legacy-deposit behavior remains valid for other purposes.

EIP-27 accounting tokens cannot be auctioned like ordinary assets. The collector
burns them and funds the existing payment obligation when the native rules apply.
Funded recreations carrying those debt tokens may be rejected by native rules;
the builder does not relax those rules to force a collection through.

## Provenance

* Node baseline: `5528ef569a41ebccbc8658212e6ee3c97d990b96`.
* Lithos baseline: `88bb1822022bf9521c281314c060a8943521d0f6`.
* Scala node/compiler: Scala 2.12.20, sigma-state 6.0.6.
* EIP-0051 PR #108, EIP-0052 PR #109 and node PR #2577 were consulted as proposals,
  not assumed to be activated consensus rules.
* Original external agent files were treated as reference material and left intact.
