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

package vinyldns.api.domain.zone

import vinyldns.core.Interfaces.Result
import vinyldns.core.domain.auth.AuthPrincipal
import vinyldns.core.domain.zone.{ACLRuleInfo, Zone, ZoneCommandResult}

trait ZoneServiceAlgebra {

  def connectToZone(zone: Zone, auth: AuthPrincipal): Result[ZoneCommandResult]

  def updateZone(newZone: Zone, auth: AuthPrincipal): Result[ZoneCommandResult]

  def deleteZone(zoneId: String, auth: AuthPrincipal): Result[ZoneCommandResult]

  def syncZone(zoneId: String, auth: AuthPrincipal): Result[ZoneCommandResult]

  def getZone(zoneId: String, auth: AuthPrincipal): Result[ZoneInfo]

  def listZones(
      authPrincipal: AuthPrincipal,
      nameFilter: Option[String],
      startFrom: Option[Int],
      maxItems: Int): Result[ListZonesResponse]

  def listZoneChanges(
      zoneId: String,
      authPrincipal: AuthPrincipal,
      startFrom: Option[String],
      maxItems: Int): Result[ListZoneChangesResponse]

  def addACLRule(
      zoneId: String,
      aclRuleInfo: ACLRuleInfo,
      authPrincipal: AuthPrincipal): Result[ZoneCommandResult]

  def deleteACLRule(
      zoneId: String,
      aclRuleInfo: ACLRuleInfo,
      authPrincipal: AuthPrincipal): Result[ZoneCommandResult]

}
