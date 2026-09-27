# Lithos auction-rent adapter

Pinned upstream: [Lithos-Client](https://github.com/Lithos-Protocol/Lithos-Client),
commit `88bb1822022bf9521c281314c060a8943521d0f6` (client version 5.5.0).

```
git clone https://github.com/Lithos-Protocol/Lithos-Client.git
cd Lithos-Client
git checkout 88bb1822022bf9521c281314c060a8943521d0f6
git apply /absolute/path/to/lithos-rent-auction.patch
sbt "testOnly transactions.rent.AuctionRentSourceSpec transactions.rent.StorageRentSourceSpec transactions.rent.StorageRentBuilderSpec"
```

On Windows, use a UTF-8 JVM (`-Dfile.encoding=UTF-8`) and
`set Test / scalacOptions ++= Seq("-encoding", "UTF-8")` if required. The node
package's `verify.py --lithos PATH` handles this and runs the selected tests.

In the Lithos configuration, opt into queue mode:

```
stratum.candidate.sources.rent {
  enabled = true
  maxTxs = 1
  maxBytes = 262144
  maxCost = 1000000
  auctionQueueDirectory = "/absolute/path/to/lithos-rent-queue"
}
```

Set the patched node's `ergo.node.rentAuctionBeneficiaryHex` to the miner's
recipient script and use that same script when preparing the collection batch.
The existing header key remains the collateral lender's key. The adapter changes
the rent source only; it preserves Lithos's candidate transport, bundle admission,
source budgets and other transaction sources.

The queue contains one `HEIGHT.json` envelope:

```json
{
  "schemaVersion": 2,
  "height": 2100001,
  "transaction": { "id": "SIGNED_TRANSACTION_ID", "inputs": [], "outputs": [] }
}
```

The transaction above is a schema illustration, not a valid claim. Generate the
real file using `rent_auction.py lithos-queue PLAN SIGNED DIRECTORY`. That command
requires a schema-2 plan, preserves proofs/extensions and writes atomically. Prepare
collections with an explicit `collector` ErgoTree as well as `beneficiary` and
`change`. The Scala builder bundles tokens across fully consumed sources by default;
optional `lots` partitions can separate valuable tokens. The fresh lot records the
collector in R9 and the collection commitment in R4. Repeated var-127 output indices
are valid for fully consumed inputs: they are baseline rent witnesses, not lot IDs.
The adapter transports these fields without decoding or reassigning them.

Only one collection is accepted per queue file. Ordinary bids, `auctions[]` batch
closes and native-fee deposit merges use the node transaction path, not this queue.
The operator's close worker sorts IDs, batches at most 32, and reduces batches by its
byte/cost budgets. There is no `--closer`: the collector return is fixed in R9.

The adapter reads asynchronously through Lithos's existing preparation worker,
accepts only the requested height, limits files to 4 MiB, checks basic rent shape,
and submits through the normal candidate-source protocol. Malformed files are
logged and yield no bundle. A missing file simply offers no rent work.

Local admission reserves the entire configured source cost budget for that batch
and overestimates wire bytes using JSON length. The patched node checks real cost,
signatures, native rent rules, beneficiary payment, lots and commitment. The queue
does not validate consensus independently and is not a substitute for node checks.

Queue mode replaces the legacy collector when configured; remove the optional
setting to use the unmodified legacy source on a network where auctions are not
active. Do not use the legacy source after auction activation. Rebuild height-bound
plans when the tip changes, and clean old queue files as an operator task.

Rent pays the configured recipient directly. This first adapter does not create
Lithos `CapitalEntry` records or automatically put rent into holding top-ups. No
transaction in the queue is broadcast to the ordinary mempool by this adapter.

The adapter suite has six transport/admission tests. Its fixture,
`test/resources/rent-auction/schema2-collection.json`, is emitted by the node's
`RentAuctionEconomicsSpec` after native `statefulValidity` and activated
`ErgoState.execTransactions` both accept the collection. It contains twenty 114-byte
aged P2PK sources, one True-script funding input, one 20-token lot with R9, and twenty
identical `127: "0300"` extensions (serialized `ShortConstant(0)`). Empty proofs are
valid for these aged sources and the True funding input; no wallet signature is
fabricated. The adapter suite checks exact transaction JSON preservation.

To regenerate the fixture, run the node's `testOnly *RentAuctionEconomicsSpec`, then
copy `target/rent-auction-bundled-collection.json` into the fixture path in the pinned
Lithos checkout. Re-run the three suites above and regenerate the patch, including
this untracked fixture. The node test is consensus evidence; the Lithos tests only
prove transport and admission. A live pool/Stratum/lender deployment remains a
separate qualification step.
