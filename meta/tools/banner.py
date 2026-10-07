"""Animated README banner for PocketStash.

The story: a phone rises out of a stitched pocket, a Stash scene grid fills its screen
(portrait and 4:3 clips letterboxed, like the app shows them), a tap opens a scene into
the player, playback starts with markers on the scrubber, a double-tap jumps +15s, a swipe
raises the volume, and the rating fills in. Feature chips on the left pop in as each
thing happens. Plays once (~6 s) and holds on the final frame.

Run from meta/tools:  python3 banner.py ../main.svg
"""
import sys

W, H = 720, 240
BG0, BG1 = "#12161c", "#0e1116"
TEXT, MUTED, AMB, GRN = "#e8e2d4", "#8e97a4", "#f0a640", "#86c17a"
PANEL, LINE, DIM = "#171c23", "#2a313c", "#252c36"
NAME_X = 214  # where "Pocket" ends and "Stash" begins

# ---------------------------------------------------------------- phone geometry
PX, PY, PW, PH = 494, 16, 120, 248          # phone body (bottom hidden in the pocket / frame)
SX, SY, SW, SH = PX + 7, PY + 9, PW - 14, 146  # screen area that matters (above the pocket)

# Scene grid: 2 columns x 3 rows of 16:9 cards.
CW, CHT, GAP = 49, 27.5, 4
GX, GY = SX + 3, SY + 17
ROWSTEP = CHT + 13
cards = []
for r in range(3):
    for c in range(2):
        cards.append((GX + c * (CW + GAP), GY + r * ROWSTEP))

# Abstract "frames" for the thumbnails: two-stop gradients.
thumbs = [
    ("#c0703a", "#3a2a4a"), ("#2f5d7a", "#13202c"), ("#7a3f63", "#1d1426"),
    ("#4d6b3a", "#16200f"), ("#8a5a2b", "#241a10"), ("#3a4f8a", "#141a2e"),
]
# Which cards hold an odd-shaped video (shown letterboxed on black): index -> inner w/h ratio.
odd = {1: 9 / 16, 4: 4 / 3}

# Player panel (the opened scene), covering the screen.
VID_H = 60
TAP = 0  # the card that gets tapped


def css() -> str:
    pops = "".join(
        f".c{i}{{animation:pop .42s cubic-bezier(.34,1.6,.64,1) {1.55 + i * 0.09:.2f}s both;"
        f"transform-origin:{x + CW / 2:.1f}px {y + CHT / 2:.1f}px}}"
        for i, (x, y) in enumerate(cards)
    )
    cx, cy = cards[TAP]
    sx, sy = CW / SW, CHT / SH
    tx, ty = cx - sx * SX, cy - sy * SY
    stars = "".join(f".s{i}{{animation:fade .18s ease {4.95 + i * 0.12:.2f}s both}}" for i in range(5))
    ticks = "".join(f".t{i}{{animation:pop .3s cubic-bezier(.34,1.8,.64,1) {3.45 + i * 0.12:.2f}s both;transform-origin:{x:.1f}px {SY + VID_H - 4:.1f}px}}"
                    for i, x in enumerate(tick_xs()))
    chips = "".join(
        f".k{i}{{animation:chip .5s cubic-bezier(.34,1.6,.64,1) {t}s both;transform-origin:{cx_:.0f}px {cy_:.0f}px}}"
        for i, (t, cx_, cy_) in enumerate(chip_anchor())
    )
    return f"""
  text{{font-family:'Segoe UI',system-ui,-apple-system,sans-serif}}
  .mono{{font-family:'JetBrains Mono','Cascadia Mono',ui-monospace,Menlo,monospace}}
  .ttl{{font-size:50px;font-weight:800;letter-spacing:-.5px}}
  .tag{{font-size:13px;fill:{MUTED}}}
  .chipt{{font-size:11.5px;font-weight:600;fill:{TEXT}}}

  @keyframes fade{{from{{opacity:0}}to{{opacity:1}}}}
  @keyframes pop{{from{{opacity:0;transform:scale(.4)}}to{{opacity:1;transform:none}}}}
  @keyframes chip{{from{{opacity:0;transform:translateY(8px) scale(.7)}}to{{opacity:1;transform:none}}}}

  /* name: "Pocket" rises, "Stash" drops in with a bounce, tagline follows */
  .tp{{animation:rise .6s cubic-bezier(.22,1.2,.36,1) .1s both}}
  .ts{{animation:drop .75s cubic-bezier(.3,1.6,.5,1) .35s both}}
  .tg{{animation:fade .6s ease .8s both}}
  @keyframes rise{{from{{opacity:0;transform:translateY(16px)}}to{{opacity:1;transform:none}}}}
  @keyframes drop{{from{{opacity:0;transform:translateY(-34px)}}to{{opacity:1;transform:none}}}}

  /* the pocket slides up, then the phone rises out of it */
  .pocket{{animation:rise .55s ease-out .35s both}}
  .phone{{animation:out 1s cubic-bezier(.25,1.25,.4,1) .75s both}}
  @keyframes out{{from{{transform:translateY(150px)}}to{{transform:none}}}}

  /* scene grid */
  .bar{{animation:fade .3s ease 1.45s both}}
  {pops}

  /* tap ripple, then the scene opens from the tapped card */
  .rip{{opacity:0;animation:rip .5s ease-out 2.5s both;transform-origin:{cx + CW / 2:.1f}px {cy + CHT / 2:.1f}px}}
  @keyframes rip{{0%{{opacity:.85;transform:scale(.2)}}100%{{opacity:0;transform:scale(1.6)}}}}
  .open{{animation:open .5s cubic-bezier(.3,1.1,.4,1) 2.78s both}}
  @keyframes open{{from{{opacity:.2;transform:translate({tx:.2f}px,{ty:.2f}px) scale({sx:.4f},{sy:.4f})}}to{{opacity:1;transform:none}}}}

  /* play button pops then gets out of the way once playback starts */
  .play{{animation:play 1s ease 3.15s both;transform-origin:{SX + SW / 2:.1f}px {SY + VID_H / 2:.1f}px}}
  @keyframes play{{0%{{opacity:0;transform:scale(.3)}}25%{{opacity:1;transform:scale(1.1)}}45%{{transform:none;opacity:1}}100%{{opacity:0;transform:scale(.9)}}}}

  /* progress: plays, jumps +15s on the double-tap, keeps playing */
  .prog{{transform-origin:{SX + 4:.1f}px 0;animation:prog 2.6s linear 3.3s both}}
  @keyframes prog{{0%{{transform:scaleX(.04)}}22%{{transform:scaleX(.26)}}26%{{transform:scaleX(.56)}}100%{{transform:scaleX(.66)}}}}
  {ticks}

  /* double-tap on the right of the video: two ripples and a "+15s" bubble */
  .d1{{opacity:0;animation:rip2 .45s ease-out 3.82s both}} .d2{{opacity:0;animation:rip2 .45s ease-out 4.02s both}}
  .d1,.d2{{transform-origin:{SX + SW - 22:.1f}px {SY + VID_H / 2:.1f}px}}
  @keyframes rip2{{0%{{opacity:.7;transform:scale(.3)}}100%{{opacity:0;transform:scale(1.5)}}}}
  .skip{{opacity:0;animation:skip 1.1s ease 3.85s both}}
  @keyframes skip{{0%{{opacity:0}}15%{{opacity:1}}75%{{opacity:1}}100%{{opacity:0}}}}

  /* swipe up on the right: the volume slider fills, then fades */
  .vol{{opacity:0;animation:skip 1.25s ease 4.45s both}}
  .volf{{transform-origin:0 {SY + VID_H - 9:.1f}px;animation:volf .7s ease-out 4.55s both}}
  @keyframes volf{{from{{transform:scaleY(.25)}}to{{transform:scaleY(.9)}}}}
  .finger{{opacity:0;animation:finger .8s ease-in-out 4.45s both}}
  @keyframes finger{{0%{{opacity:0;transform:translateY(14px)}}20%{{opacity:.9}}80%{{opacity:.9}}100%{{opacity:0;transform:translateY(-10px)}}}}

  /* details under the video, then the rating fills star by star */
  .meta{{animation:fade .4s ease 3.25s both}}
  {stars}

  /* feature chips on the left, in step with the phone */
  {chips}
"""


def tick_xs():
    track0, track1 = SX + 4, SX + SW - 4
    return [track0 + (track1 - track0) * f for f in (0.18, 0.41, 0.63, 0.84)]


CHIPS = [
    # (appears at, label, icon glyph, x, y, width)
    (1.85, "8 libraries", "▦", 48, 146, 118),
    (3.4, "resume where you left off", "▶", 176, 146, 206),
    (3.95, "double-tap −5s / +15s", "»", 48, 180, 182),
    (5.05, "rate · edit · mark", "★", 240, 180, 152),
]


def chip_anchor():
    return [(t, x + w / 2, y + 12) for (t, _, _, x, y, w) in CHIPS]


def star(cx: float, cy: float, r: float) -> str:
    import math
    pts = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append(f"{cx + rr * math.cos(a):.2f},{cy + rr * math.sin(a):.2f}")
    return " ".join(pts)


def build() -> str:
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">']
    out.append("  <defs>")
    out.append(f'    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{BG0}"/><stop offset="1" stop-color="{BG1}"/></linearGradient>')
    out.append(f'    <clipPath id="frame"><rect width="{W}" height="{H}" rx="18"/></clipPath>')
    out.append(f'    <clipPath id="screen"><rect x="{SX}" y="{SY}" width="{SW}" height="{PH}" rx="8"/></clipPath>')
    for i, (a, b) in enumerate(thumbs):
        out.append(f'    <linearGradient id="g{i}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient>')
    out.append(f'    <linearGradient id="pk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1d232c"/><stop offset="1" stop-color="{PANEL}"/></linearGradient>')
    out.append(f'    <linearGradient id="shade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".55"/></linearGradient>')
    out.append("    <style>" + css() + "    </style>")
    out.append("  </defs>")
    out.append(f'  <rect width="{W}" height="{H}" rx="18" fill="url(#bg)"/>')
    out.append('  <g clip-path="url(#frame)">')

    # ---------------- left: name, tagline, chips
    # The two words meet at a shared x, so they line up whatever font the viewer falls back to.
    out.append(f'  <text class="ttl tp" x="{NAME_X}" y="86" text-anchor="end" fill="{TEXT}">Pocket</text>')
    out.append(f'  <text class="ttl ts" x="{NAME_X + 1}" y="86" text-anchor="start" fill="{AMB}">Stash</text>')
    out.append(f'  <text class="tag tg" x="48" y="114">Your whole Stash library, in your pocket.</text>')
    for i, (_, label, glyph, x, y, w) in enumerate(CHIPS):
        out.append(
            f'  <g class="k{i}"><rect x="{x}" y="{y}" width="{w}" height="24" rx="12" fill="{PANEL}" stroke="{LINE}" stroke-width="1.2"/>'
            f'<text x="{x + 13}" y="{y + 16.3}" class="chipt" fill="{AMB}" style="fill:{AMB}">{glyph}</text>'
            f'<text x="{x + 27}" y="{y + 16.3}" class="chipt">{label}</text></g>'
        )

    # ---------------- right: phone (drawn first) rising out of the pocket (drawn on top)
    out.append('  <g class="phone">')
    out.append(f'    <rect x="{PX}" y="{PY}" width="{PW}" height="{PH}" rx="17" fill="#0a0c10" stroke="#39414d" stroke-width="2"/>')
    out.append(f'    <rect x="{PX + PW / 2 - 14}" y="{PY + 3.5}" width="28" height="3.5" rx="1.75" fill="#20262f"/>')
    out.append('    <g clip-path="url(#screen)">')
    out.append(f'      <rect x="{SX}" y="{SY}" width="{SW}" height="{PH}" fill="{BG1}"/>')
    # app bar
    out.append(f'      <g class="bar"><text x="{SX + 4}" y="{SY + 10}" style="font-size:7.5px;font-weight:800" fill="{TEXT}">Pocket<tspan fill="{AMB}">Stash</tspan></text>'
               f'<circle cx="{SX + SW - 9}" cy="{SY + 7.5}" r="3" fill="none" stroke="{MUTED}" stroke-width="1"/></g>')
    # grid
    for i, (x, y) in enumerate(cards):
        g = [f'      <g class="c{i}">']
        if i in odd:
            r = odd[i]
            g.append(f'<rect x="{x}" y="{y}" width="{CW}" height="{CHT}" rx="3" fill="#050506"/>')
            if r < 1:  # portrait, pillarboxed
                iw = CHT * r
                g.append(f'<rect x="{x + (CW - iw) / 2:.2f}" y="{y}" width="{iw:.2f}" height="{CHT}" fill="url(#g{i})"/>')
            else:      # 4:3, pillarboxed
                iw = CHT * r
                g.append(f'<rect x="{x + (CW - iw) / 2:.2f}" y="{y}" width="{iw:.2f}" height="{CHT}" fill="url(#g{i})"/>')
        else:
            g.append(f'<rect x="{x}" y="{y}" width="{CW}" height="{CHT}" rx="3" fill="url(#g{i})"/>')
        # duration pill, progress on a couple, title lines
        g.append(f'<rect x="{x + CW - 14}" y="{y + CHT - 7}" width="12" height="5" rx="1.5" fill="#000" fill-opacity=".7"/>')
        if i in (0, 3):
            g.append(f'<rect x="{x}" y="{y + CHT - 1.6}" width="{CW * (0.6 if i == 0 else 0.3):.1f}" height="1.6" fill="{AMB}"/>')
        g.append(f'<rect x="{x}" y="{y + CHT + 3}" width="{CW * 0.86:.1f}" height="3" rx="1.5" fill="#3a4250"/>')
        g.append(f'<rect x="{x}" y="{y + CHT + 8}" width="{CW * 0.55:.1f}" height="2.4" rx="1.2" fill="{DIM}"/>')
        g.append("</g>")
        out.append("".join(g))
    cx, cy = cards[TAP]
    out.append(f'      <circle class="rip" cx="{cx + CW / 2}" cy="{cy + CHT / 2}" r="14" fill="#fff" fill-opacity=".6"/>')

    # opened scene (player + details)
    out.append('      <g class="open">')
    out.append(f'        <rect x="{SX}" y="{SY}" width="{SW}" height="{PH}" fill="{BG1}"/>')
    out.append(f'        <rect x="{SX}" y="{SY}" width="{SW}" height="{VID_H}" fill="url(#g{TAP})"/>')
    out.append(f'        <rect x="{SX}" y="{SY + VID_H - 18}" width="{SW}" height="18" fill="url(#shade)"/>')
    # scrubber with progress, markers
    ty_ = SY + VID_H - 5
    out.append(f'        <rect x="{SX + 4}" y="{ty_ - 1}" width="{SW - 8}" height="2" rx="1" fill="#fff" fill-opacity=".28"/>')
    out.append(f'        <rect class="prog" x="{SX + 4}" y="{ty_ - 1}" width="{SW - 8}" height="2" rx="1" fill="{AMB}"/>')
    for i, x in enumerate(tick_xs()):
        out.append(f'        <rect class="t{i}" x="{x - 0.8:.1f}" y="{ty_ - 3.2}" width="1.6" height="6.4" rx=".8" fill="#fff"/>')
    # play button
    pcx, pcy = SX + SW / 2, SY + VID_H / 2
    out.append(f'        <g class="play"><circle cx="{pcx}" cy="{pcy}" r="11" fill="{AMB}"/>'
               f'<path d="M{pcx - 3.5},{pcy - 5.5} L{pcx + 5.5},{pcy} L{pcx - 3.5},{pcy + 5.5} Z" fill="#15110c"/></g>')
    # double-tap ripples + bubble
    dcx = SX + SW - 22
    out.append(f'        <circle class="d1" cx="{dcx}" cy="{pcy}" r="12" fill="#fff" fill-opacity=".5"/>')
    out.append(f'        <circle class="d2" cx="{dcx}" cy="{pcy}" r="12" fill="#fff" fill-opacity=".5"/>')
    out.append(f'        <g class="skip"><path d="M{SX + SW},{SY + 6} h-30 a24,24 0 0 0 0,{VID_H - 16} h30 z" fill="#fff" fill-opacity=".16"/>'
               f'<text x="{SX + SW - 15}" y="{pcy + 3}" text-anchor="middle" style="font-size:8.5px;font-weight:800" fill="#fff">+15s</text></g>')
    # volume swipe: slider on the right edge, finger dot
    vx = SX + SW - 7
    out.append(f'        <g class="vol"><rect x="{vx - 3.5}" y="{SY + 9}" width="7" height="{VID_H - 22}" rx="3.5" fill="#000" fill-opacity=".55"/>'
               f'<rect x="{vx - 1.2}" y="{SY + 12}" width="2.4" height="{VID_H - 28}" rx="1.2" fill="#fff" fill-opacity=".25"/>'
               f'<rect class="volf" x="{vx - 1.2}" y="{SY + 12}" width="2.4" height="{VID_H - 28}" rx="1.2" fill="{AMB}"/></g>')
    out.append(f'        <circle class="finger" cx="{vx - 14}" cy="{SY + 40}" r="5" fill="#fff" fill-opacity=".55"/>')
    # details
    my = SY + VID_H + 8
    out.append(f'        <g class="meta">'
               f'<rect x="{SX + 4}" y="{my}" width="{SW * 0.78:.1f}" height="4.5" rx="2.2" fill="{TEXT}" fill-opacity=".85"/>'
               f'<rect x="{SX + 4}" y="{my + 8}" width="{SW * 0.45:.1f}" height="3" rx="1.5" fill="{MUTED}"/>'
               + "".join(f'<circle cx="{SX + 10 + k * 15}" cy="{my + 35}" r="5.5" fill="url(#g{(k + 2) % 6})" stroke="{LINE}"/>' for k in range(4))
               + "".join(f'<rect x="{SX + 4 + k * 26}" y="{my + 46}" width="22" height="7" rx="3.5" fill="{DIM}"/>' for k in range(4))
               + f'<rect x="{SX + 4}" y="{my + 58}" width="{SW - 8}" height="2.5" rx="1.2" fill="{DIM}"/></g>')
    for i in range(5):
        x = SX + 8 + i * 11
        out.append(f'        <polygon points="{star(x, my + 20, 4.4)}" fill="{DIM}"/>')
        out.append(f'        <polygon class="s{i}" points="{star(x, my + 20, 4.4)}" fill="{AMB}"/>')
    out.append("      </g>")  # open
    out.append("    </g>")    # screen clip
    out.append("  </g>")      # phone

    # pocket, in front of the phone
    out.append('  <g class="pocket">')
    out.append(f'    <path d="M450,176 Q554,190 658,176 L658,252 L450,252 Z" fill="url(#pk)" stroke="{LINE}" stroke-width="1.5"/>')
    out.append(f'    <path d="M460,186 Q554,199 648,186" fill="none" stroke="{AMB}" stroke-width="1.4" stroke-dasharray="5 4" stroke-linecap="round" opacity=".75"/>')
    out.append(f'    <path d="M462,192 L462,250 M646,192 L646,250" fill="none" stroke="{AMB}" stroke-width="1.4" stroke-dasharray="5 4" stroke-linecap="round" opacity=".5"/>')
    out.append(f'    <circle cx="458" cy="181" r="2.6" fill="{AMB}"/><circle cx="650" cy="181" r="2.6" fill="{AMB}"/>')
    out.append("  </g>")

    out.append("  </g>")  # frame clip
    out.append("</svg>")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "../main.svg"
    with open(path, "w", encoding="utf-8") as f:
        f.write(build())
    print("wrote", path)
