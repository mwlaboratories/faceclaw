# Local build environment for this fork (branch `mwlabs`): not part of
# upstream. `mwlabs` = upstream/main + our not-yet-merged fixes; this file is
# one more commit on top that faceclaw-update rebases along.
#
#   faceclaw-update      fetch upstream, rebase mwlabs, build release, install
#   faceclaw-build       build the release APK (signed with ~/.config/faceclaw)
#   faceclaw-install     install it over the phone's app (same key: settings stay)
#   faceclaw-reinstall   one-time, when the signing key changed
#   faceclaw-config-pull / -push, faceclaw-log
#
# The signing key and settings exports live in ~/.config/faceclaw, outside any
# repo: a lost key means uninstalling the app (and re-pairing) once.
{ pkgs, config, ... }:

{
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_22;
    npm.enable = true;
  };

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
  };
  android = {
    enable = true;
    platforms.version = [ "36" "35" ]; # 35: @nativescript/core's plugin build
    # 35.0.0: native/kotlin's AGP default. NDK + cmake: the on-phone model
    # (libfaceclaw_llama.so) is compiled from llama.cpp on the first build.
    buildTools.version = [ "36.0.0" "35.0.0" "35.0.1" ];
    emulator.enable = false;
    systemImages.enable = false;
    ndk.enable = true;
    sources.enable = false;
    googleAPIs.enable = false;
    android-studio.enable = false;
  };
  packages = [ pkgs.python3 ]; # faceclaw/scripts/push_config.sh validates configs with it

  env.FACECLAW_DIR = config.devenv.root;

  scripts.faceclaw-build = {
    description = "Build FaceClaw (release) from ../faceclaw, signed with ~/.config/faceclaw";
    exec = ''
      set -euo pipefail
      : "''${FACECLAW_HOME:=$HOME/.config/faceclaw}"
      ks="$FACECLAW_HOME/release.keystore"
      mkdir -p "$FACECLAW_HOME" && chmod 700 "$FACECLAW_HOME"
      if [ ! -f "$ks" ]; then
        echo "faceclaw: new signing key $ks (keep it: a new key means reinstalling the app)"
        keytool -genkeypair -keystore "$ks" -storepass faceclaw -keypass faceclaw -alias faceclaw \
          -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=mwlabs FaceClaw" >/dev/null
      fi
      cd "$FACECLAW_DIR"
      [ -d node_modules ] || npm ci
      # upstream scripts use #!/bin/bash; NixOS has none
      bash ./build.sh --release --key-store-path "$ks" --key-store-password faceclaw \
        --key-store-alias faceclaw --key-store-alias-password faceclaw
    '';
  };

  scripts.faceclaw-install = {
    description = "Install the built FaceClaw over the one on the phone (same key: settings stay)";
    exec = ''
      set -euo pipefail
      : "''${FACECLAW_HOME:=$HOME/.config/faceclaw}"
      apk="$FACECLAW_DIR/platforms/android/app/build/outputs/apk/release/app-release.apk"
      if ! out=$(adb install -r "$apk" 2>&1); then
        echo "$out" >&2
        case "$out" in *UPDATE_INCOMPATIBLE*|*signatures*)
          echo "faceclaw: the phone's app has another signing key; run faceclaw-reinstall once" >&2 ;;
        esac
        exit 1
      fi
      adb shell monkey -p com.faceclaw.app -c android.intent.category.LAUNCHER 1 >/dev/null
      echo "faceclaw: installed $(git -C "$FACECLAW_DIR" rev-parse --short HEAD)"
    '';
  };

  scripts.faceclaw-update = {
    description = "Pull upstream FaceClaw, rebase our fixes (branch mwlabs) on it, build, install";
    exec = ''
      set -euo pipefail
      : "''${FACECLAW_HOME:=$HOME/.config/faceclaw}"
      cd "$FACECLAW_DIR"
      if ! git diff --quiet || ! git diff --cached --quiet; then
        echo "faceclaw: uncommitted changes in $FACECLAW_DIR; commit or stash them first" >&2
        exit 1
      fi
      git fetch -q upstream && git fetch -q origin
      git checkout -q mwlabs
      before=$(git rev-parse --short HEAD)
      # Fixes upstream has merged drop out here by themselves.
      git rebase -q upstream/main
      git push -q --force-with-lease origin mwlabs
      echo "faceclaw: mwlabs $before -> $(git rev-parse --short HEAD) (upstream $(git rev-parse --short upstream/main))"
      echo "our fixes on top:"; git log --oneline upstream/main..mwlabs | sed 's/^/  /'
      faceclaw-build
      faceclaw-install
    '';
  };

  scripts.faceclaw-reinstall = {
    description = "One-time: export settings, uninstall, install, restore (when the signing key changed)";
    exec = ''
      set -euo pipefail
      : "''${FACECLAW_HOME:=$HOME/.config/faceclaw}"
      faceclaw-config-pull
      adb uninstall com.faceclaw.app
      faceclaw-install
      faceclaw-config-push
      echo "faceclaw: settings restored; pair the glasses (and T3) again if FaceClaw asks"
    '';
  };

  scripts.faceclaw-config-pull = {
    description = "Export FaceClaw settings (incl. API keys) to ~/.config/faceclaw/settings.json";
    exec = ''
      set -euo pipefail
      : "''${FACECLAW_HOME:=$HOME/.config/faceclaw}"
      mkdir -p "$FACECLAW_HOME" && chmod 700 "$FACECLAW_HOME"
      umask 077
      bash "$FACECLAW_DIR/scripts/pull_config.sh" "$FACECLAW_HOME/settings.json" "$@" >/dev/null
      echo "faceclaw: settings saved to $FACECLAW_HOME/settings.json"
    '';
  };

  scripts.faceclaw-config-push = {
    description = "Restore FaceClaw settings from ~/.config/faceclaw/settings.json";
    exec = ''bash "$FACECLAW_DIR/scripts/push_config.sh" "''${FACECLAW_HOME:-$HOME/.config/faceclaw}/settings.json" "$@"'';
  };

  scripts.faceclaw-log = {
    description = "Follow FaceClaw logcat";
    exec = ''adb logcat "$@" | grep --line-buffered -iE "faceclaw|JS:"'';
  };
}
