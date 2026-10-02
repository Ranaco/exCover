# exCover on Google Play

Everything to paste into Play Console. Graphics are in this folder (`python tools/play_assets.py`
rebuilds them).

## App details

| Field | Value |
|---|---|
| App name (30) | `exCover` |
| Package | `com.ranaco.excover` |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Category | Personalization |
| Tags | Wallpapers, Live wallpapers, Personalization |
| Contact email | ranasatyamraj@gmail.com |
| Website | https://github.com/Ranaco/exCover |
| Privacy policy | https://github.com/Ranaco/exCover/blob/main/PRIVACY.md |

### Short description (80)

```
Animated GIFs, clock themes and a GIF AOD for your Razr's cover screen.
```

### Full description

```
The Razr has a great outside screen, and Motorola only lets you put a photo on it. exCover puts animated GIFs there instead.

ON THE COVER SCREEN
• GIFs stay animated on the cover home and lock screens, not a still frame.
• Five clock themes right inside Motorola's own Themes picker: Airy, Classic, Poster, Mono and GIF only. Change their font and colour just like Motorola's.
• Your GIF on the always-on display: when the cover sleeps, the clock fades and the GIF eases into a dimmed still frame.
• Six always-on looks, most of them built to save battery.

IN THE APP
• Find GIFs in Photos, search GIPHY right inside the app, or paste any GIF link.
• Frame each GIF with pinch and drag. The cover and the taller main screen get their own framing.
• Set cover home, cover lock, main home and main lock separately.
• Swipe between your recent GIFs, and keep favourites for good.
• Once a GIF is imported, everything works offline.

PRIVATE BY DESIGN
No account, no ads, no tracking. Your GIFs stay on your phone.

WORKS ON
Motorola Razr phones with a cover screen, such as the Razr 50 Ultra and Razr 50. No root and no computer needed.

exCover is open source (MIT): github.com/Ranaco/exCover

exCover is an independent app and isn't made or endorsed by Motorola. GIFs from GIPHY.
```

### Graphics

| Asset | File |
|---|---|
| App icon 512×512 | `icon-512.png` |
| Feature graphic 1024×500 | `feature-graphic.png` |
| Phone screenshots (1080×1920) | `screenshot-1.png` … `screenshot-7.png` |

## Store settings

- **Countries:** all.
- **Device catalog:** exCover only does something on Razr phones. In *Reach and devices → Device
  catalog*, exclude everything except Motorola Razr models with a cover screen, so nobody else
  installs it and leaves a one-star review.

## Content rating (IARC questionnaire)

- Category: **All other app types**.
- Violence, sexuality, language, controlled substances, gambling: **No** to all.
- Users can interact or share content: **No**.
- Shares location: **No**. Digital purchases: **No**.
- Unrestricted internet access: **Yes**. GIPHY search and GIF links can show any GIF on the web.
  GIPHY results are filtered to rating **pg-13**.

## Target audience

- Age groups: **13–15, 16–17, 18 and over**. Not directed at children.

## Data safety

- Does the app collect or share user data? **No.**
  - GIPHY search words go straight from your phone to GIPHY when you search. That's a request you
    make yourself, so Play counts it as neither collected nor shared by exCover. Nothing goes to
    the developer.
- Is data encrypted in transit? **Yes** (HTTPS to GIPHY).
- Can users request deletion? Nothing is collected. Uninstalling deletes everything.

## Other declarations

- **Ads:** No.
- **Government app:** No. **Financial features:** None. **Health:** None.
- **News app:** No.
- **Permissions:** only internet access. No sensitive-permission declarations are needed.

## Signing (important)

exCover is already on GitHub, signed with `~/.android-keys/excover-release.jks`. In Play App
Signing, choose **"Use a different key" → upload your existing app signing key** (Play gives you a
PEPK command to export it). The Play version and GitHub APKs then share one signature, so people
can switch between them without uninstalling. If Google generates a new key instead, the two can
never update each other.

Upload: `app/build/outputs/bundle/release/app-release.aab` (`./gradlew bundleRelease`).
