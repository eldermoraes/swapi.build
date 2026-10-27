# shellcheck shell=bash
# The <main id="main-content"> of an HTML document, for the probes that check
# the content the server draws (2.11.0+): scripts/verify-deploy.sh (origin mode
# and the www follow in redirect mode) and the "Native smoke" step of
# .github/workflows/native-binary.yml. Sourced, never run. Tested offline by
# scripts/main-text.test.sh.
#
# Why not a sed range (/<main/,/<\/main>/p): on a one-line empty <main></main>
# the range opens and never closes on that line, so it runs to the end of the
# document and counts the footer as content.

# main_text: reads a document on stdin; prints the text between the end of the
# opening tag of <main id="main-content"> and the first </main>, without tags,
# whitespace squeezed. Without that opening tag it prints nothing (never the
# whole document).
main_text() {
  local doc
  doc=$(tr '\n\r' '  ')
  case "$doc" in
    *'<main id="main-content"'*) ;;
    *) return 0 ;;
  esac
  printf '%s' "$doc" \
    | sed -e 's/.*<main id="main-content"[^>]*>//' -e 's/<\/main>.*//' \
    | sed 's/<[^>]*>//g' \
    | tr -s '[:space:]' ' ' \
    | sed -e 's/^ //' -e 's/ $//'
}

# main_html (2.12.0): reads a document on stdin; prints the markup between the
# end of the opening tag of <main id="main-content"> and the first </main>, on
# one line (the links of a record live in tags main_text drops). Without that
# opening tag it prints nothing.
main_html() {
  local doc
  doc=$(tr '\n\r' '  ')
  case "$doc" in
    *'<main id="main-content"'*) ;;
    *) return 0 ;;
  esac
  printf '%s' "$doc" | sed -e 's/.*<main id="main-content"[^>]*>//' -e 's/<\/main>.*//'
}

# main_open_tag: reads a document on stdin; prints the opening tag of
# <main id="main-content"> (where the data-ssr-page marker lives), or nothing.
main_open_tag() {
  tr '\n\r' '  ' | grep -o '<main id="main-content"[^>]*>' | head -1
}
