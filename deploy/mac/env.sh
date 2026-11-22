# Sourced by the other scripts: PATH and JAVA_HOME for Homebrew tools (launchd starts with a bare PATH).
for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
  if [[ -x "$b" ]]; then eval "$("$b" shellenv)"; break; fi
done
if command -v brew >/dev/null 2>&1 && [[ -d "$(brew --prefix)/opt/openjdk@21" ]]; then
  export JAVA_HOME="$(brew --prefix)/opt/openjdk@21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH="$HOME/.local/bin:$HOME/.claude/local:$PATH"
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
