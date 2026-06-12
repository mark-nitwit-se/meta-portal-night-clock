# meta-portal-night-clock

A vibe coded nightstand alarm clock for the (unlocked) Meta Portal. Big dimmable
clock, gentle one-shot alarms that wake you with a soft ambient melody, and a
snooze budget so a forgotten alarm can't ring all day.

Bootstrapped from Meta's [portal-samples](https://github.com/meta-quest/portal-samples)
(MIT, see LICENSE).

## Audio attribution

The alarm melody is
["Éléments Auditifs - Aube Électronique"](https://freesound.org/people/kjartan_abel/sounds/710950/)
by [Kjartan Abel](https://freesound.org/people/kjartan_abel/), licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

## Building

JDK 17 + Android SDK, then:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
