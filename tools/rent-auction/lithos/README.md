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
  "height": 2100001,
  "transaction": { "id": "SIGNED_TRANSACTION_ID", "inputs": [], "outputs": [] }
}
```

The transaction above is a schema illustration, not a valid claim. Generate the
real file using `rent_auction.py lithos-queue PLAN SIGNED DIRECTORY`. That command
preserves proofs/extensions and writes atomically. A single collection transaction
can include multiple source boxes and lots.

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

Validation: the adapter was compiled inside the pinned real client, and its four
queue/admission tests ran with 35 existing rent tests. A live pool/Stratum/lender
deployment is a separate qualification step.
