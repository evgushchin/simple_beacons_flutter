import Foundation
import CoreLocation

struct ItemConstant {
  static let nameKey = "name"
  static let uuidKey = "uuid"
}

class Item: NSObject, NSCoding {
  let name: String
  let uuid: UUID
  var beacon: CLBeacon?

  // init(name: String,  uuid: UUID, majorValue: Int, minorValue: Int) {
  init(name: String,  uuid: UUID) {
    self.name = name
    self.uuid = uuid
  }

  // MARK: NSCoding
  required init(coder aDecoder: NSCoder) {
    let aName = aDecoder.decodeObject(forKey: ItemConstant.nameKey) as? String
    name = aName ?? ""

    let aUUID = aDecoder.decodeObject(forKey: ItemConstant.uuidKey) as? UUID
    uuid = aUUID ?? UUID()
  }

  func encode(with aCoder: NSCoder) {
    aCoder.encode(name, forKey: ItemConstant.nameKey)
    aCoder.encode(uuid, forKey: ItemConstant.uuidKey)
  }

  func asBeaconRegion() -> CLBeaconRegion {
      if #available(iOS 13.0, *) {
          return CLBeaconRegion(uuid: uuid, identifier: name)
      } else {
          return CLBeaconRegion(proximityUUID: uuid, identifier: name)
      }
  }

  func locationString() -> String {
    guard let beacon = beacon else { return "Location: Unknown" }
    let accuracy = String(format: "%.2f", beacon.accuracy)
    return "\(accuracy)"
  }

  func nameForProximity(_ proximity: CLProximity) -> String {
    switch proximity {
    case .unknown:
      return "Unknown"
    case .immediate:
      return "Immediate"
    case .near:
      return "Near"
    case .far:
      return "Far"
    @unknown default:
        return "Unknown"
    }
  }

}

func ==(item: Item, beacon: CLBeacon) -> Bool {
    if #available(iOS 13.0, *) {
        return (beacon.uuid.uuidString == item.uuid.uuidString)
    } else {
        // Fallback on earlier versions
        return (beacon.proximityUUID.uuidString == item.uuid.uuidString)
    }
}
