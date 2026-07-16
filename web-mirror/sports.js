// Mirrors the scoringModel + scoreIncrements fields from the Android app's bundled
// app/src/main/assets/sports/*.json. There is no shared config source between the Android
// app and this web client yet — if a sport's JSON changes, update this file by hand too.
// Object.create(null) so lookups like SPORTS["constructor"] (an untrusted "sport" value
// synced in from Firebase) can never resolve via the prototype chain.
export const SPORTS = Object.assign(Object.create(null), {
  basketball:  { displayName: "Basketball",   scoringModel: "flat",     scoreIncrements: [1, 2, 3] },
  generic:     { displayName: "Generic",      scoringModel: "flat",     scoreIncrements: [1] },
  hockey:      { displayName: "Hockey",       scoringModel: "flat",     scoreIncrements: [1] },
  soccer:      { displayName: "Soccer",       scoringModel: "flat",     scoreIncrements: [1] },
  volleyball:  { displayName: "Volleyball",   scoringModel: "setsGames", scoreIncrements: [1] },
  badminton:   { displayName: "Badminton",    scoringModel: "setsGames", scoreIncrements: [1] },
  squash:      { displayName: "Squash",       scoringModel: "setsGames", scoreIncrements: [1] },
  tabletennis: { displayName: "Table Tennis", scoringModel: "setsGames", scoreIncrements: [1] },
});

export const DEFAULT_SPORT = SPORTS.generic;
