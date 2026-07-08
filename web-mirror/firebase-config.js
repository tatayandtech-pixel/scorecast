// Firebase project config for the web mirror client (spec §9 / Phase 5 web extension).
//
// projectId, storageBucket, and databaseURL are project-level and safe to copy from
// app/google-services.json. apiKey/authDomain/appId/messagingSenderId are PER-APP —
// the Android app's values from google-services.json must not be reused here.
//
// One-time setup (free, no billing impact — still the Spark plan):
//   Firebase console -> Project settings -> Your apps -> Add app -> Web (</>)
//   -> register e.g. "ScoreCast Mirror Web" -> copy the generated config below.
export const firebaseConfig = {
  apiKey: "AIzaSyAD7JtWSKzkoqykwxbyRejU0Ww1dtc_wmg",
  authDomain: "scorecast-app-625c0.firebaseapp.com",
  databaseURL: "https://scorecast-app-625c0-default-rtdb.asia-southeast1.firebasedatabase.app",
  projectId: "scorecast-app-625c0",
  storageBucket: "scorecast-app-625c0.firebasestorage.app",
  messagingSenderId: "452923426459",
  appId: "1:452923426459:web:2701b41feac2f23cd4283e",
};
