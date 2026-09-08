# NoBonk website

Public website: https://nobonk.genwhy.ai

NoBonk by Krishav: Android background obstacle alerts, real phone recordings, foreground setup and testing, an educational alert-policy demo, and Google Play closed-test signup. The iPhone development preview has a detailed capability section for foreground People/Fast Objects, Browse & scan and native message composition. Public iPhone distribution is still coming soon. Creator videos show the current walkthrough and original school-project pitch.

## Website source

Download and extract `nobonk-website-source.zip` in this directory. It contains the complete React/Vinext project, lockfile, local WebP assets, Blender render script, and editable `.blend` scenes. Requires Node 22.13+. Run `npm ci`, `npm run dev`, and `npm run build`; static output is `dist/client`.

The alert demo follows `AlertPolicy.kt` at app revision `23a5452db093a8547e63ddd80adad8c0884debae`: person frame height thresholds of 28%, 42%, and 60% at the 2 m reference sensitivity, one-level approach escalation, and a HIGH override for imminent contact. The full app includes additional tracking, filtering, and timing. The site labels its simulation and reported test results explicitly.

The website does not access your camera. Its approved phone recordings and screenshots demonstrate the app; Blender illustrations are labeled as concepts. Review-only assets, historical patch files and deployment account metadata are excluded from the public source archive. No passwords or signing material are included. Public support uses support@genwhy.ai.

Website source revision: `b3038c0afe59d960ae18bf973ca02eeaecb33dac`. This archive includes the corrected background-use and audio-direction descriptions; it is a source snapshot, not an indication of Google Play review approval.

## Brand consistency

Use `public/images/icon-v2/nobonk-crisp-play-512.png` as the approved app icon. The repository README copy at `docs/images/nobonk-icon.png` is byte-identical. Website header and footer use `app/brand.tsx`; the download section, reviewer page and browser icons use that same artwork. Keep functional control symbols and authentic recordings distinct from app branding.
