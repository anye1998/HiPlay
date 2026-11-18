# Reference protocol sources (test only)

These three files are verbatim copies of the reference receiver's protocol layer:

- upstream: https://github.com/huducat586/hicar-android-receiver
- files: `app/src/local/hicarlab/NearbyWire.java`, `SessionCipher.java`, `HiSightWire.java`
- licence: MIT (the upstream project states it derives from Aki894/CarLink, also MIT)

They are here so `HiCarHiSightReferenceParityTest` can run the reference implementation beside this
repository's port on the same bytes, instead of trusting that the port is faithful.

Only two mechanical changes were made, both marked in the files:

1. `package local.hicarlab` → `package com.shilapi.xcertplay.hicar.reference`;
2. `final class` / package-private members → `public`, so a test in another package can construct and
   drive them.

No behaviour was changed. They are test sources: they are not compiled into the APK, and nothing in
`src/main` refers to them.
