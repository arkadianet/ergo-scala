# Submission notes and suggested descriptions

Author: [arkadianet](https://github.com/arkadianet). EIP number: unassigned.
The material below provides suggested descriptions for technical review submissions.

## EIP repository

Suggested title: **Draft: producer-attested storage-rent auctions funding the EIP-27 reserve**

Suggested description:

> This draft requires transferable tokens in fully consumed storage-rent boxes to
> enter bounded public auctions. Nodes verify bids, refunds, settlement and token
> disposition without estimating token prices. Collections bundle fully consumed
> sources into lots. Close returns seed principal and unused allowance to the
> recorded collector. The bid funds a collector share, token carrier and merge fee;
> the remaining proceeds must increase the existing re-emission reserve. Unbid lots
> burn their tokens on close. The existing rent age remains unchanged.
>
> Ordinary rent pays a producer-committed beneficiary independent of the header
> public key. The reference package includes height-gated Scala node enforcement,
> contracts, funded builders, wallet support, schema-2 CLI/indexer/batch-close tooling,
> and a tested Lithos candidate-source adapter. Activation and EIP number are unassigned.
>
> Review is requested particularly for the provisional extension keys shared with
> EIP-0052, the 0.05 ERG bid floor, 10% share and self-bidding edge, close allowance,
> provisional close-cost calibration, producer reserve-merge ordering, and
> compatibility with any separate EIP-48 activation. The comparison with EIP-0051
> acknowledges that it also does not require consensus price discovery.
>
> The regenerated verification report records 217 passed and one pre-existing
> ignored test. The regenerated machine-readable report is authoritative for an
> artifact. This is a reference implementation, not activation or an independent audit.

Copy `EIP-XXXX.md` into the EIP repository under the filename agreed with its
maintainers. Include the implementation URL when a review branch is published.
Until then, the supplied archive and patches are the reviewable implementation.
Do not label the proposal Implemented/Activated or assign an existing EIP number.

## Node implementation

Suggested title: **Add opt-in reference implementation for storage-rent auctions**

Suggested description:

> Fully consumed rent boxes can currently surrender their tokens without a public
> sale. This change adds disabled-by-default consensus restrictions requiring
> transaction-wide token accounting, a producer rent commitment/payment, refundable
> seed and disjoint batch-close payouts. Net proceeds after explicit bid-funded
> costs enter a reserve-increase covenant. UTXO and digest validation, candidate
> assembly, mempool policy and wallet signing follow the same activation setting.
>
> The implementation preserves current rent fee arithmetic and native EIP-27 debt
> redemption. It adds no new script version or assigned mainnet activation. CLI,
> indexer, transaction builders and reproduction instructions ship with the EIP.
>
> Reviewer-reported selected results: core 44 plus one ignored, root 50, prover 4,
> candidate/wallet 47, Python 31 and patched Lithos 41. Persistent tests use synthetic
> UTXO snapshots and fake PoW. Regenerate `docs/rent-auction/verification.json` for
> exact commands and artifact identity; this description does not certify a stale report.
> See `ECONOMICS.md` for measured collection results and `REVIEW.md` for cost calibration.

The patch targets node commit `5528ef569a41ebccbc8658212e6ee3c97d990b96` in the
provided checkout. Rebase and reverify against the target upstream branch before
submitting a node PR if its base differs. Preserve the fixture database isolation
change in `ErgoWalletServiceSpec` or submit that test fix separately with its reason.

## Lithos implementation

Suggested title: **Add optional height-bound queue source for rent-auction collections**

Suggested description:

> Queue mode loads one signed, funded schema-2 bundled collection for the requested
> candidate height, carries its collector register and repeated rent witnesses,
> applies existing bundle budgets, and preserves Lithos's collateral-lender
> header key and candidate transport. The patched node enforces auction mapping
> and the separately configured rent beneficiary. Omitting the queue setting retains
> the current legacy collector.
>
> The reviewer's clean run reports six adapter tests and 35 existing rent tests
> passing at the pinned client revision. The adapter pays rent directly to the
> configured beneficiary; holding top-up accounting and automatic reserve-merge rebasing are outside this
> adapter. Live pool/lender qualification remains necessary before deployment.

Apply the separate patch at the pinned Lithos revision documented in its README.
