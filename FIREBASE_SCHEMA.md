# Firebase schema for Wasalny app

## 1. users

Collection: `users`

Document ID: Firebase Auth UID

Example:

```json
{
  "uid": "abc123",
  "role": "customer",
  "name": "أحمد",
  "phone": "+966500000000",
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

Notes:
- role can be `customer` or `driver`; administrator access is stored separately in `admins`
- customer and driver can share same authentication UID

## 2. admins

Collection: `admins`

Document ID: Firebase Auth UID

Example:

```json
{
  "uid": "admin123",
  "role": "admin",
  "active": true,
  "createdAt": "timestamp"
}
```

Notes:
- This is the account used by the admin login screen.
- The admin login checks `isAdmin(uid)`.

## 3. drivers

Collection: `drivers`

Document ID: Firebase Auth UID

Example:

```json
{
  "uid": "driver123",
  "displayName": "سائق 1",
  "phone": "+966500000000",
  "licenseType": "مرخص",
  "vehicleType": "توك توك",
  "idCardImageUrl": "https://...",
  "vehicleImageUrl": "https://...",
  "approved": false,
  "available": false,
  "lat": 31.27133,
  "lon": 30.786165,
  "geohash": "swy...",
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

Notes:
- approved = false until admin approves
- available = true only when driver is active and approved
- geohash is used for nearby driver search

## 4. rides

Collection: `rides`

Document ID: auto-generated ride ID

Example:

```json
{
  "customerId": "customer123",
  "customerName": "أحمد",
  "fromAddress": "ميدان السلام",
  "toAddress": "مركز المدينة",
  "fromLat": 31.24,
  "fromLon": 30.77,
  "toLat": 31.31,
  "toLon": 30.80,
  "distanceKm": 5.8,
  "femaleMode": false,
  "withLuggage": false,
  "bookingType": "now",
  "status": "searching",
  "searchRadiusMeters": 500,
  "searchStage": 0,
  "scheduledAt": null,
  "invitedDriverIds": [],
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

Valid statuses:
- searching
- scheduled
- offered
- accepted
- driver_arriving
- driver_arrived
- in_progress
- completed
- no_drivers
- cancelled

The search worker adds fields such as `invitedDriverIds`, `searchWorkerActive`, and `searchWorkerStartedAt`. The selected driver and agreed price are stored as `selectedDriverId`, `selectedOfferId`, and `selectedPrice` after an offer is accepted. Scheduled rides use `bookingType: "school"` and a timestamp in `scheduledAt`.

The customer's phone number is stored only in `rides/{rideId}/private/contact`, not in the readable ride document.

## 5. rides/{rideId}/offers

Collection: `rides/{rideId}/offers`

Document ID: driver UID

Example:

```json
{
  "driverId": "driver123",
  "driverName": "سائق 1",
  "price": 50,
  "etaMinutes": 8,
  "status": "pending",
  "createdAt": "timestamp"
}
```

Valid statuses:
- pending
- selected

## 6. drivers/{uid}/requests

Collection: `drivers/{uid}/requests`

Document ID: ride ID

Example:

```json
{
  "rideId": "ride123",
  "customerId": "customer456",
  "fromAddress": "منطقة 1",
  "toAddress": "منطقة 2",
  "fromLat": 31.24,
  "fromLon": 30.77,
  "distanceKm": 5.8,
  "femaleMode": false,
  "withLuggage": false,
  "radiusMeters": 500,
  "status": "searching",
  "createdAt": "timestamp"
}
```

## 7. rides/{rideId}/private/contact

Example:

```json
{
  "customerPhone": "+966500000000"
}
```

This is used to reveal customer contact only to the selected driver.

## 8. Required Firebase setup

1. Enable Phone Authentication
2. Enable Firestore Database
3. Enable Storage if you want to store uploaded images
4. Download `google-services.json` and place it into `app/`
5. Add the admin UID manually to `admins`

## 9. Required Android setup

Create `local.properties` in project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Example:

```properties
sdk.dir=/home/codespace/Android/Sdk
```

## 10. Admin creation example

After a user signs in with Firebase Auth, create a document in `admins`:

```json
{
  "uid": "UID_FROM_FIREBASE_AUTH",
  "role": "admin",
  "active": true
}
```

Then the admin login screen can verify using `repository.isAdmin(uid)`.
