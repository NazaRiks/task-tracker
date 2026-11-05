// onMouseDown для кнопок: клик мышью не передаёт фокус, поэтому рамка после клика не остаётся.
// С клавиатуры фокус и рамка (:focus-visible) работают как обычно; сам click не отменяется.
export const noMouseFocus = (e) => e.preventDefault()
