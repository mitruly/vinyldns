/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.domain.batch

import java.util.UUID

import vinyldns.api.VinylDNSConfig
import vinyldns.api.domain.ReverseZoneHelpers
import vinyldns.api.domain.batch.BatchChangeInterfaces.ValidatedBatch
import vinyldns.api.domain.dns.DnsConversions.getIPv6FullReverseName
import vinyldns.core.domain.batch._
import vinyldns.core.domain.record.{RecordData, RecordSet, RecordSetChange}
import vinyldns.core.domain.record.RecordType._
import vinyldns.core.domain.zone.Zone
import vinyldns.core.domain.record.RecordType.RecordType

object SupportedBatchChangeRecordTypes {
  val supportedTypes = Set(A, AAAA, CNAME, PTR, TXT, MX)
  def get: Set[RecordType] = supportedTypes
}

/* Helper types for intermediate transformations of the batch data */
// ALL of these are subject to change as implementation needs
object BatchTransformations {

  final case class ExistingZones(zones: Set[Zone]) {
    val zoneMap: Map[String, Zone] = zones.map(z => (z.name.toLowerCase(), z)).toMap

    def getById(id: String): Boolean = zones.exists(zn => zn.id.equals(id))

    def getByName(name: String): Option[Zone] = zoneMap.get(name.toLowerCase())

    def getipv4PTRMatches(ipv4: String): List[Zone] =
      zones.filter { zn =>
        ReverseZoneHelpers.ipIsInIpv4ReverseZone(zn, ipv4)
      }.toList

    def getipv6PTRMatches(ipv6: String): List[Zone] = {
      val fullReverseZone = getIPv6FullReverseName(ipv6)
      fullReverseZone.toList.flatMap { fqdn =>
        zones.filter(zn => fqdn.endsWith(zn.name))
      }
    }
  }

  final case class ExistingRecordSets(recordSets: List[RecordSet]) {
    val recordSetMap: Map[(String, String), List[RecordSet]] =
      recordSets.groupBy(rs => (rs.zoneId, rs.name.toLowerCase))

    def get(zoneId: String, name: String, recordType: RecordType): Option[RecordSet] =
      recordSetMap.getOrElse((zoneId, name.toLowerCase), List()).find(_.typ == recordType)

    def get(recordKey: RecordKey): Option[RecordSet] =
      get(recordKey.zoneId, recordKey.recordName, recordKey.recordType)

    def containsRecordSetMatch(zoneId: String, name: String): Boolean =
      recordSetMap.contains(zoneId, name.toLowerCase)

    def getRecordSetMatch(zoneId: String, name: String): List[RecordSet] =
      recordSetMap.getOrElse((zoneId, name.toLowerCase), List())
  }

  sealed trait ChangeForValidation {
    val zone: Zone
    val recordName: String
    val inputChange: ChangeInput
    val recordKey = RecordKey(zone.id, recordName, inputChange.typ)
    def asStoredChange(changeId: Option[String] = None): SingleChange
    def isAddChangeForValidation: Boolean
  }

  object ChangeForValidation {
    def apply(zone: Zone, recordName: String, changeInput: ChangeInput): ChangeForValidation =
      changeInput match {
        case a: AddChangeInput => AddChangeForValidation(zone, recordName, a)
        case d: DeleteRRSetChangeInput =>
          DeleteRRSetChangeForValidation(zone, recordName, d)
        // TODO: Support DeleteRecordChangeInput in ChangeForValidation
        case _: DeleteRecordChangeInput =>
          throw new UnsupportedOperationException(
            "DeleteRecordChangeInput is not yet implemented/supported in VinylDNS.")
      }
  }

  final case class AddChangeForValidation(
      zone: Zone,
      recordName: String,
      inputChange: AddChangeInput,
      existingRecordTtl: Option[Long] = None)
      extends ChangeForValidation {
    def asStoredChange(changeId: Option[String] = None): SingleChange = {

      val ttl = inputChange.ttl.orElse(existingRecordTtl).getOrElse(VinylDNSConfig.defaultTtl)

      SingleAddChange(
        Some(zone.id),
        Some(zone.name),
        Some(recordName),
        inputChange.inputName,
        inputChange.typ,
        ttl,
        inputChange.record,
        SingleChangeStatus.Pending,
        None,
        None,
        None,
        List.empty,
        changeId.getOrElse(UUID.randomUUID().toString)
      )
    }

    def isAddChangeForValidation: Boolean = true
  }

  final case class DeleteRRSetChangeForValidation(
      zone: Zone,
      recordName: String,
      inputChange: DeleteRRSetChangeInput)
      extends ChangeForValidation {
    def asStoredChange(changeId: Option[String] = None): SingleChange =
      SingleDeleteRRSetChange(
        Some(zone.id),
        Some(zone.name),
        Some(recordName),
        inputChange.inputName,
        inputChange.typ,
        SingleChangeStatus.Pending,
        None,
        None,
        None,
        List.empty,
        changeId.getOrElse(UUID.randomUUID().toString)
      )

    def isAddChangeForValidation: Boolean = false
  }

  final case class DeleteRecordChangeForValidation(
      zone: Zone,
      recordName: String,
      inputChange: DeleteRecordChangeInput)
      extends ChangeForValidation {
    def asStoredChange(changeId: Option[String] = None): SingleChange =
      SingleDeleteRecordChange(
        Some(zone.id),
        Some(zone.name),
        Some(recordName),
        inputChange.inputName,
        inputChange.typ,
        inputChange.record,
        SingleChangeStatus.Pending,
        None,
        None,
        None,
        List.empty,
        changeId.getOrElse(UUID.randomUUID().toString)
      )

    def isAddChangeForValidation: Boolean = false
  }

  final case class BatchConversionOutput(
      batchChange: BatchChange,
      recordSetChanges: List[RecordSetChange])

  final case class ChangeForValidationMap(
      changes: List[ChangeForValidation],
      existingRecordSets: ExistingRecordSets) {
    val innerMap: Map[RecordKey, ValidationChanges] = {
      changes.groupBy(_.recordKey).map { keyChangesTuple =>
        val (recordKey, changeList) = keyChangesTuple
        val (addChanges, deleteChangeList) =
          changeList.partition(_.isAddChangeForValidation)
        val existingRecords = existingRecordSets.get(recordKey).map(_.records).getOrElse(List())

        // Grab record entries and ttls from add changes
        val (addChangeRecordDataList, addChangeTtlList) = addChanges.collect {
          case add: AddChangeForValidation => (add.inputChange.record, add.inputChange.ttl)
        }.unzip

        // Get exhaustive list of which DNS entries are being deleted
        val deleteChangeSet = deleteChangeList
          .collect {
            case _: DeleteRRSetChangeForValidation =>
              existingRecords
            case del: DeleteRecordChangeForValidation => List(del.inputChange.record)
          }
          .flatten
          .toSet

        (
          recordKey,
          ValidationChanges(
            addChangeRecordDataList.toSet,
            deleteChangeSet,
            existingRecords.toSet,
            addChangeTtlList.toSet,
            deleteChangeList.nonEmpty
          )
        )
      }
    }

    def getChangeForValidationChanges(recordKey: RecordKey): ValidationChanges =
      innerMap.getOrElse(
        recordKey,
        ValidationChanges(
          Set(),
          Set(),
          existingRecordSets.get(recordKey).map(_.records).getOrElse(List()).toSet,
          Set(),
          false))

    def getChangeForValidationAdds(recordKey: RecordKey): Set[RecordData] =
      getChangeForValidationChanges(recordKey).addChanges

    def getChangeForValidationDeletes(recordKey: RecordKey): Set[RecordData] =
      getChangeForValidationChanges(recordKey).deleteChanges

    def addChangesNotUnique(recordKey: RecordKey): Boolean = {
      val validationChanges = getChangeForValidationChanges(recordKey)
      validationChanges.addChanges.size > 1 || validationChanges.ttls.size > 1
    }

    def containsAddChanges(recordKey: RecordKey): Boolean =
      getChangeForValidationChanges(recordKey).addChanges.nonEmpty

    // There is a distinction between having a delete in the batch and having a valid delete
    def containsValidDeleteChanges(recordKey: RecordKey): Boolean =
      getChangeForValidationChanges(recordKey).deleteChanges.nonEmpty
  }

  final case class ValidationChanges(
      addChanges: Set[RecordData],
      deleteChanges: Set[RecordData],
      existingRecords: Set[RecordData],
      ttls: Set[Option[Long]], // Only kept track of for adds
      containsDeletes: Boolean)

  final case class BatchValidationFlowOutput(
      validatedChanges: ValidatedBatch[ChangeForValidation],
      existingZones: ExistingZones,
      existingRecordSets: ExistingRecordSets,
      changeGroups: ChangeForValidationMap
  )
}
