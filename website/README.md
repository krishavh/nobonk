# NoBonk website

Public website: https://nobonk.genwhy.ai

An interactive introduction to NoBonk by Krishav, with original Blender artwork, an educational demonstration of the person-alert ladder, reported prototype results, and a Coming soon on Google Play finish.

## Website source

Download and extract `nobonk-website-source.zip` in this directory. It contains the complete React/Vinext project, lockfile, local WebP assets, Blender render script, and both editable `.blend` scenes. Requires Node 22.13+. Run `npm ci`, `npm run dev`, and `npm run build`; static output is `dist/client`.

The alert demo follows `AlertPolicy.kt` at app revision `23a5452db093a8547e63ddd80adad8c0884debae`: person frame height thresholds of 28%, 42%, and 60% at the 2 m reference sensitivity, one-level approach escalation, and a HIGH override for imminent contact. The full app includes additional tracking, filtering, and timing. The site labels its simulation and reported test results explicitly.

No camera access, passwords, signing material, or personal contact details are included. The Blender illustrations are conceptual, not app screenshots.

Website source revision: `3f43e8c90e5a4e36254c6189b22de9e9b42243ad`.
