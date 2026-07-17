// Mirrors the scoringModel + scoreIncrements fields from the Android app's bundled
// app/src/main/assets/sports/*.json. There is no shared config source between the Android
// app and this web client yet — if a sport's JSON changes, update this file by hand too.
// Object.create(null) so lookups like SPORTS["constructor"] (an untrusted "sport" value
// synced in from Firebase) can never resolve via the prototype chain.
// setsGames fields (bestOf/pointsToWinGame/finalSetPoints/winByTwo/pointCap/periods) are copied
// verbatim from the Android app's app/src/main/assets/sports/*.json — keep them in sync by hand
// (see the file-level note above) and mirror GameStateHolder.addSetsGamesScore's exact rule in
// app.js's bumpSetsGamesScore, not a re-derived approximation.
export const SPORTS = Object.assign(Object.create(null), {
  basketball:  { displayName: "Basketball",   scoringModel: "flat",     scoreIncrements: [1, 2, 3] },
  generic:     { displayName: "Generic",      scoringModel: "flat",     scoreIncrements: [1] },
  hockey:      { displayName: "Hockey",       scoringModel: "flat",     scoreIncrements: [1] },
  soccer:      { displayName: "Soccer",       scoringModel: "flat",     scoreIncrements: [1] },
  volleyball:  {
    displayName: "Volleyball", scoringModel: "setsGames", scoreIncrements: [1],
    periods: 5, periodLabel: "Set", bestOf: 5, pointsToWinGame: 25, finalSetPoints: 15,
    winByTwo: true, pointCap: null,
  },
  badminton: {
    displayName: "Badminton", scoringModel: "setsGames", scoreIncrements: [1],
    periods: 3, periodLabel: "Game", bestOf: 3, pointsToWinGame: 21, finalSetPoints: null,
    winByTwo: true, pointCap: 30,
  },
  squash: {
    displayName: "Squash", scoringModel: "setsGames", scoreIncrements: [1],
    periods: 5, periodLabel: "Game", bestOf: 5, pointsToWinGame: 11, finalSetPoints: null,
    winByTwo: true, pointCap: null,
  },
  tabletennis: {
    displayName: "Table Tennis", scoringModel: "setsGames", scoreIncrements: [1],
    periods: 5, periodLabel: "Game", bestOf: 5, pointsToWinGame: 11, finalSetPoints: null,
    winByTwo: true, pointCap: null,
  },
});

export const DEFAULT_SPORT = SPORTS.generic;
