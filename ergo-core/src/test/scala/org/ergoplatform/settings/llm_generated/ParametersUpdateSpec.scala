package org.ergoplatform.settings.llm_generated

import org.ergoplatform.settings.ErgoValidationSettingsUpdate
import org.ergoplatform.settings.MainnetLaunchParameters
import org.ergoplatform.settings.Parameters
import org.ergoplatform.settings.VotingSettings
import org.ergoplatform.utils.ErgoCorePropertyTest

class ParametersUpdateSpec extends ErgoCorePropertyTest {
  import Parameters.{BlockVersion, SoftForkStartingHeight, SoftForkVotesCollected}

  private val parameterId: Byte = 9
  private val emptyUpdate = ErgoValidationSettingsUpdate.empty
  private val votingSettings = VotingSettings(10, 2, 1, 1000, "")
  private val version4Table = Parameters.DefaultParameters.updated(BlockVersion, 4)
  private val missingIdTable = version4Table - parameterId
  private val activationTable = missingIdTable
    .updated(BlockVersion, 3)
    .updated(SoftForkStartingHeight, 10)
    .updated(SoftForkVotesCollected, 20)

  private def update(
      table: Map[Byte, Int],
      height: Int = 40,
      proposed: ErgoValidationSettingsUpdate = emptyUpdate
  ): (Parameters, ErgoValidationSettingsUpdate) = {
    Parameters(0, table, emptyUpdate).update(
      height, false, Seq.empty, proposed, votingSettings
    )
  }

  property("id 9 is present from genesis with 64 and survives update") {
    Parameters.SubblocksPerBlockIncrease shouldBe parameterId
    Parameters.DefaultParameters(parameterId) shouldBe 64
    MainnetLaunchParameters.subBlocksPerBlock shouldBe 64
    val (updated, activated) = MainnetLaunchParameters.update(
      10, false, Seq.empty, emptyUpdate, votingSettings
    )
    updated.parametersTable shouldBe Parameters.DefaultParameters
    updated.subBlocksPerBlock shouldBe 64
    activated shouldBe emptyUpdate
  }

  property("version 4 injects 30 when id 9 is absent") {
    val (updated, activated) = update(missingIdTable)
    updated.parametersTable shouldBe missingIdTable.updated(parameterId, 30)
    updated.subBlocksPerBlock shouldBe 30
    updated.height shouldBe 40
    activated shouldBe emptyUpdate
  }

  property("version 4 preserves an existing non-default id 9 value") {
    val table = version4Table.updated(parameterId, 42)
    val (updated, activated) = update(table)
    updated.parametersTable shouldBe table
    updated.subBlocksPerBlock shouldBe 42
    activated shouldBe emptyUpdate
  }

  property("before version 4 activation an absent id 9 stays absent") {
    val table = missingIdTable.updated(BlockVersion, 3)
    val (updated, activated) = update(table)
    updated.parametersTable shouldBe table
    updated.subBlocksPerBlockOpt shouldBe None
    activated shouldBe emptyUpdate
  }

  property("successful version 4 activation injects 30 in the same update") {
    val (updated, activated) = update(activationTable)
    updated.parametersTable shouldBe activationTable
      .updated(BlockVersion, 4).updated(parameterId, 30)
    updated.subBlocksPerBlock shouldBe 30
    activated shouldBe emptyUpdate
  }

  property("activating an update disabling rule 409 suppresses id 9 injection") {
    val disabled = ErgoValidationSettingsUpdate(Seq(409.toShort), Seq.empty)
    val (updated, activated) = update(activationTable, proposed = disabled)
    updated.parametersTable shouldBe activationTable.updated(BlockVersion, 4)
    updated.subBlocksPerBlockOpt shouldBe None
    activated shouldBe disabled

    // The suppression currently applies only to the activating update.
    val (next, nextActivated) = updated.update(
      50, false, Seq.empty, disabled, votingSettings
    )
    next.parametersTable shouldBe missingIdTable.updated(parameterId, 30)
    next.subBlocksPerBlock shouldBe 30
    nextActivated shouldBe emptyUpdate
  }

  property("a merely proposed rule 409 disable does not suppress injection") {
    val disabled = ErgoValidationSettingsUpdate(Seq(409.toShort), Seq.empty)
    val (updated, activated) = update(missingIdTable, proposed = disabled)
    updated.parametersTable shouldBe missingIdTable.updated(parameterId, 30)
    activated shouldBe emptyUpdate
  }
}
