/**
 *  Navimow HA Gate
 *
 *  Brain on Hubitat. Weather from a Hubitat weather device.
 *  Home Assistant is used only for the NavimowHA lawn_mower
 *  entity (state, battery, start, dock).
 *
 *  1.12.4 Debt clears when week maps reach the schedule, not after those mow hours.
 *  1.12.3 Weather debt adds map fraction to the cap and carries until mowed.
 *  1.12.2 Ahead of pace still shows the next leave. Monday first light.
 *  1.12.1 Reset map zeros the open virtual map. Does not store it. Week pace stays.
 *  1.11 HA path recorded, not used for est. Light off is first–last; on is catch-up until dew.
 *       Grass dew 3/5. Film walks day and night. Sit is film minus charge plus 15 min. No 8 h clip.
 *       Done band 80–120 until a sample over 50%, then 90–110. Jobs only from a real done.
 *       Hub timezone. Learned pack rates. Leave pack is last docked poll. Poke HA on send.
 *  1.10 Light toggle + makeup to dew. Learned times, week/life hours, blades, HA backfill.
 *       Grass dew, rain film/ET0, 36 h dewSpreads. Jobs only 0–100. Typed % is the phone.
 *       Status TIME + GO/NO-GO glance. Config trim. HA poke on send.
 *  1.9  Film walk after last drop; gauge/leaf first. Hubitat start → charge.
 *       Done: type 100, or sit film leftover + 15 min after leave % in band.
 *       Band 90–110 / 95–105. Jobs store hours + est error.
 *  1.8  Full-map job hours. Rain film vs ET0. Sensors over Meteo.
 *  1.7  Return hours, poll clock, sit/instant done, growth rain, Need cap.
 *  1.6  Hybrid who, service hold, cycle 100%, MQTT stale, Status/Detail.
 *  1.5  Dock-who, charge hold, late slice, learned leave/return.
 *  1.4  Snap Est to typed %. Rate from 2nd sample.
 *  1.3  Mow vs dock hours. Leave/return averages.
 *  1.2  Pair calibration. Reject reset estimates.
 *  1.1  Mon–Sun week. Ahead. nextStart/nextEnd.
 *  1.12.11 Map finish walks 7 days. Leftover cut reads beyond 1 week.
 *  1.12.10 Map finish uses a morning dew clear inside the day. A skipped day is not beyond 2 weeks.
 *  1.12.8 A closed map waits for the next first light. An open map can still run until dew.
 *  1.12.7 Cap is the day/7 line. The 0.35 map buffer is gone.
 *  1.12.6 Map finish walks 14 days. Later days end at last light, not before dew clear.
 *  1.0  Dew, rain lock, growth target, HA start/dock.
 *
 */

def appVersion() { "1.12.11" }

definition(
    name: "Navimow HA Gate",
    namespace: "brian.navimow",
    author: "Brian",
    description: "Hubitat weather + dew/rain/last-light gate; start/dock Navimow through Home Assistant",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "statusPage")
    page(name: "detailPage")
    page(name: "configPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "App", install: true, uninstall: true) {
        section("<b>Now</b>") {
            paragraph haMainLine()
        }
        section("<b>Progress</b>") {
            paragraph haProgressLine()
            input "calPctNow", "decimal", title: calHelpText(), description: calFieldTitle(), required: false, disabled: !haIsDocked()
            paragraph "Est ${atomicState.estJobPct == null ? "?" : atomicState.estJobPct}% from sample ${calSampleText()}."
            input "resetMap", "bool", title: "Reset map to 0%", defaultValue: false
            paragraph "Zeros this virtual map. Does not store the open hours. Start the new map in Navimow too."
            input "undoLastCal", "bool", title: "Overwrite last sample", defaultValue: false
            paragraph "Type the correct % above, check this, Done. Replays rate from the sample before the bad one."
        }
        section("<b>Control</b>") {
            input "enabled", "bool", title: "App enabled", defaultValue: true
            input "forceDock", "bool", title: "Force dock", defaultValue: false
            input "serviceHold", "bool", title: "Service hold", defaultValue: false
            paragraph "Off the charger to clean. No start or dock. Time is Service h. Who becomes gate when this goes off."
            paragraph serviceCountdownText()
            paragraph bladeCountdownText()
            input "replaceBlades", "bool", title: "Blades replaced", defaultValue: false, disabled: serviceHold != true
            paragraph "Locked unless Service hold is on."
        }
        section("Pages") {
            href "statusPage", title: "Status", description: "Live decisions"
            href "detailPage", title: "Detail", description: "Labeled reference"
            href "configPage", title: "Config", description: "HA, devices, lawn, thresholds"
        }
        section("<b>APP</b>") {
            paragraph "VERSION: ${appVersion()}"
        }
    }
}

def statusForecastLines() {
    def raw = "${atomicState.forecastText ?: ""}"
    if (!raw.trim()) return "none"
    def parts = raw.split(/[|\n]/)
    def dated = []
    def other = []
    parts.each { p ->
        def s = "${p}".trim()
        if (!s) return
        if (s =~ /^\d{4}-\d{2}-\d{2}/) dated << s
        else other << s
    }
    if (dated) return dated.join("\n")
    if (other) return other.join("\n")
    return raw
}

def statusForecastShort() {
    def raw = "${atomicState.forecastText ?: ""}"
    if (!raw.trim()) return "none"
    def parts = raw.split(/[|\n]/)
    def dated = []
    parts.each { p ->
        def s = "${p}".trim()
        if (s =~ /^\d{4}-\d{2}-\d{2}/) dated << s
    }
    if (dated.size() >= 2) return dated[0] + "\n" + dated[1]
    if (dated) return dated[0]
    return statusForecastLines()
}

def statusHaBit() {
    def st = "${atomicState.mowerState ?: ''}"
    def ha = "${atomicState.haStatus ?: ''}"
    if (!ha || ha == st) return ""
    return " · HA: ${ha}"
}

def statusLateLine() {
    if (atomicState.lateSlice == true || atomicState.sliceNeedBatt != null)
        return "\nLate slice: ${atomicState.lateSlice ? "yes" : "no"} · need ${atomicState.sliceNeedBatt == null ? "—" : atomicState.sliceNeedBatt + "%"}"
    return ""
}

def statusLightPhrase() {
    if (lightWindowOnly()) return atomicState.inWindow ? "in window" : "after last light"
    if (lightAllowsWork()) return atomicState.inWindow ? "in window · makeup on" : "makeup after light"
    return "outside · on pace"
}

def statusTimeline() {
    def rows = []
    def add = { ms, name ->
        if (ms == null) return
        try { rows << [ms: (ms as long), name: name] } catch (ignored) {}
    }
    try { cycleFinishText() } catch (ignored) {}
    try { nextMowText() } catch (ignored) {}
    add(now(), "now")
    add(atomicState.cycleFinishMs, "map 100%")
    add(atomicState.nextMowMs, "leave")
    add(firstLightMs(), "first light")
    if (atomicState.dewOn == true) add(now(), "dew on")
    else add(eveningDewOnMs(), "next dew")
    add(nextDewClearMs(), "dew clear")
    try {
        def ss = getSunriseAndSunset()
        def pad = (afterSunsetMin() as int) * 60000L
        add(ss.sunset.time + pad, "last light")
    } catch (ignored) {}
    add(atomicState.lastMowStartMs, "last out")
    add(atomicState.lastMowDockMs, "last back")
    add(firstTodayMs(), "first today")
    if (atomicState.mowerState == "mowing") {
        try {
            def packH = (atomicState.mowHoursLeft ?: 0) as double
            if (packH > 0) add(now() + ((packH * 3600000L) as long), "home pack")
        } catch (ignored) {}
    }
    try {
        def rainMs = hourlyRainClearMs()
        if (rainMs != null && rainMs > now()) add(rainMs, "rain clear")
    } catch (ignored) {}
    rows.sort { a, b -> (a.ms as long) <=> (b.ms as long) }
    def from = now() - (12L * 3600000L)
    def to = now() + (24L * 3600000L)
    def groups = []
    rows.each { r ->
        if ((r.ms as long) < from || (r.ms as long) > to) return
        def key = fmtClock(r.ms)
        def last = groups ? groups[groups.size() - 1] : null
        if (last && last.key == key) {
            if (!(last.names.contains(r.name))) last.names << r.name
        } else {
            groups << [key: key, names: [r.name], now: (r.name == "now")]
        }
    }
    def lines = []
    groups.each { g ->
        def mark = g.now ? ">" : " "
        lines << "${mark}${g.key}  ${g.names.join(" · ")}"
    }
    if (!lines) return "<b>TIME</b>\n—"
    return "<b>TIME</b>\n" + lines.join("\n")
}

def goNoGo() {
    if (atomicState.shouldRun == true) return "GO"
    return "NO-GO"
}

def whoSendPhrase() {
    def w = "${atomicState.dockWho ?: ''}"
    if (w == "charge") return "Navimow sends"
    if (w == "gate") return "Hubitat sends"
    return w ?: "—"
}

def statusFlagLine() {
    def bits = []
    if (atomicState.mowAhead) bits << "ahead"
    if (atomicState.rainLock) bits << "rain lock ${d2(atomicState.waitHours)}h"
    if (frostLock()) bits << "frost"
    if (haStale()) bits << "HA stale"
    if (mqttStale()) bits << "MQTT quiet"
    if (serviceHold == true) bits << "service hold"
    if (atomicState.todayPrecious) bits << "precious"
    if (atomicState.chargeHold) bits << "charge hold"
    if (!bits) return "Clear"
    return bits.join(" · ")
}

def slVal(v) {
    if (v == true) return "yes"
    if (v == false) return "no"
    if (v == null || v == "") return "—"
    return "${v}"
}

def sl(a, v, s, d) {
    def extra = (d && "${d}" != "") ? " — ${d}" : ""
    return "${a}: ${slVal(v)}${extra}"
}

def slSub(a, v, d = "") {
    def extra = (d && "${d}" != "") ? " — ${d}" : ""
    return "· ${a}: ${slVal(v)}${extra}"
}

def slIf(show, a, v, d) { show ? sl(a, v, "", d) + "\n" : "" }

def slIfSub(show, a, v, d = "") { show ? slSub(a, v, d) + "\n" : "" }

def haMainLine() {
    def st = atomicState.mowerState ?: "?"
    def batt = atomicState.battery
    def battS = (batt == null) ? "—" : "${batt}%"
    def extra = ""
    if (serviceHold == true) extra = extra + " · service hold"
    if (haStale()) extra = extra + " · stale"
    return "HA: ${st} · ${battS}${extra}"
}

def d2(v) {
    if (v == null || v == "") return "?"
    try { return rnd(v as double, 2) } catch (ignored) { return v }
}

def statusPage() {
    dynamicPage(name: "statusPage", title: "Status") {
        section("Status") {
            paragraph "${statusTimeline()}\n<b>GO/NO-GO</b>\n${goNoGo()}: ${holdReason()}\nLEAVE: ${atomicState.nextMow ?: "—"}\nHOME: ${atomicState.nextDock ?: "—"}\nREADY: ${atomicState.nextStart ?: "—"}\nLIGHT: ${statusLightPhrase()}\nPACE: ${atomicState.catchUp ? "behind week" : "on pace"}\nREMARK: ${statusFlagLine()}\n<b>MOWER</b>\nSTATE: ${atomicState.mowerState ?: "—"}${statusHaBit()}\nBATTERY: ${atomicState.battery ?: "—"}%\nSEND: ${whoSendPhrase()}\nLEAVE%: Navimow ${atomicState.leaveBatt ?: "—"}%   Hubitat ${atomicState.leaveTarget ?: "—"}%\nHOME%: Navimow ${atomicState.returnBatt ?: "—"}%\n<b>MAP</b>\nPROGRESS: ${atomicState.estJobPct == null ? "—" : atomicState.estJobPct}%\nDONE: ${doneBandText()}%\nMAPS: ${atomicState.passesDone} / ${atomicState.targetPasses}\n<b>WX</b>\nSKY: ${atomicState.weatherState ?: "—"}\nTEMP: ${atomicState.tempF ?: "—"} F\n<b>APP</b>\nVERSION: ${appVersion()}"
        }
    }
}

def detailPage() {
    dynamicPage(name: "detailPage", title: "Detail") {
        section("GO/NO-GO", hideable: true, hidden: false) { paragraph detailDecision() + detailSchedule() }
        section("MOWER", hideable: true, hidden: true) { paragraph detailMower() }
        section("MAP / WEEK", hideable: true, hidden: true) { paragraph detailWeek() }
        section("SLICES", hideable: true, hidden: true) { paragraph detailSlices() }
        section("GROWTH", hideable: true, hidden: true) { paragraph detailGrowth() }
        section("WX", hideable: true, hidden: true) { paragraph detailWx() }
        section("BLADES", hideable: true, hidden: true) {
            bladeCurveLines().each { line ->
                paragraph line
            }
        }
        section("APP", hideable: true, hidden: true) { paragraph detailDebug() }
    }
}

def detailDecision() {
    return """
${sl("CALL", "${goNoGo()}  ${holdReason()}", "", "")}
${sl("YARD", atomicState.lawnDry ? "dry" : "wet", "", "")}
${slSub("rain lock", atomicState.rainLock ? "${d2(atomicState.waitHours)} h" : "off")}
${slSub("leaf", "${atomicState.leafPct ?: "—"}% (${atomicState.leafSrc ?: "—"})")}
${sl("LIGHT", statusLightPhrase(), "", "")}
${slSub("rule", lightWindowOnly() ? "stop at last light" : "keep going until dew if behind")}
${sl("PACE", atomicState.catchUp ? "behind week" : "on pace", "", "Under today's cap, or this map still open.")}
${slSub("pull extra now", atomicState.mowAhead ? "yes, rain later in the week" : "no")}
${slSub("precious day", atomicState.todayPrecious ? "yes" : "no")}
${slSub("carry", atomicState.carryNote ?: "none")}
${slIf(frostLock(), "FROST", "yes", "Air 36 F.")}${slIf(serviceHold == true, "SERVICE", "hold", "")}${slIf(haStale(), "HA", "stale", "")}${slIf(mqttStale(), "MQTT", "quiet", "")}"""
}

def detailSchedule() {
    return """
${sl("LEAVE", atomicState.nextMow ?: "—", "", "")}
${sl("HOME", atomicState.nextDock ?: "—", "", "")}
${sl("READY", atomicState.nextStart ?: "—", "", "When a leave is allowed. Not whether it is cutting now.")}
${slSub("dew", dewOnText())}
${slSub("next dew", nextDewText())}
${slSub("dew clear", dewClearText())}
${slSub("rain", rainOffText())}
${slSub("first light", fmtClock(firstLightMs()))}
${sl("ENDS", nextEndText(), "", "")}
${slSub("light left today", ((remainingLightH() ?: 0) as double) > 0 ? "${d2(remainingLightH())} h" : "after last light")}
${sl("MAP 100%", atomicState.cycleFinish ?: "—", "", "")}
"""
}

def detailMower() {
    def ha = "${atomicState.haStatus ?: ''}"
    def st = "${atomicState.mowerState ?: ''}"
    def late = (atomicState.lateSlice == true || atomicState.sliceNeedBatt != null)
    def outing = atomicState.outingBy == "charge" ? "Navimow" : (atomicState.outingBy == "gate" ? "Hubitat" : (atomicState.outingBy ?: "—"))
    return """
${sl("STATE", "${atomicState.mowerState ?: "—"}   ${atomicState.battery ?: "—"}%", "", "")}
${slIf(ha && ha != st, "HA", ha, "")}${sl("SEND", whoSendPhrase(), "", "")}
${slSub("this outing started by", outing)}
${slIf(atomicState.chargeHold, "CHARGE", "holding for leave %", "")}${sl("LEAVE%", "Navimow ${atomicState.leaveBatt ?: "—"}%   avg ${atomicState.avgLeaveBatt ?: "—"}%", "", "")}
${sl("HOME%", "Navimow ${atomicState.returnBatt ?: "—"}%   avg ${atomicState.avgReturnBatt ?: "—"}%", "", "")}
${slSub("Hubitat leave", "${atomicState.leaveTarget ?: "—"}%")}
${slSub("time to leave %", "${d2(atomicState.hoursToFull)} h")}
${slSub("cut left this pack", "${d2(atomicState.mowHoursLeft)} h")}
${slSub("charge rate", "${atomicState.chargePctPerHour ?: "—"} %/h")}
${slSub("mow drain", "${atomicState.mowPctPerHour ?: "—"} %/h")}
${slIf(late, "LATE SLICE", "need ${atomicState.sliceNeedBatt ?: "—"}%   ${atomicState.sliceHours ?: "—"} h", "")}${sl("OUT", edgeClock(atomicState.lastMowStartMs), "", "")}
${sl("BACK", edgeClock(atomicState.lastMowDockMs), "", "")}
${slSub("first today", edgeClock(firstTodayMs()))}
${sl("TIMES last/avg", "mow ${d2(atomicState.lastMowSliceH)}/${d2(atomicState.avgMowSliceH)} h   charge ${d2(atomicState.lastChargeH)}/${d2(atomicState.avgChargeH)} h   return ${d2(atomicState.lastReturnTripH)}/${d2(atomicState.avgReturnTripH)} h", "", "Navimow-owned outings only.")}
"""
}

def detailWeek() {
    return """
${sl("MAP", "${d2(atomicState.estJobPct)}%", "", "")}
${slSub("last typed", calSampleText())}
${slSub("hours on this map", "${d2(atomicState.jobMowH)} h")}
${slSub("done window", "${doneBandText()}%")}
${slSub("typed samples", atomicState.calN ?: 0)}
${sl("WEEK", "${atomicState.passesDone} / ${atomicState.targetPasses} maps", "", "")}
${slSub("today cap", "${atomicState.pacedCap} maps")}
${slSub("yard acres", "${d2(lawnAcres)} ac")}
${slSub("hours for one map", "${d2(atomicState.hoursPerPass ?: hoursPerPass())} h")}
${slSub("acres per mow hour", "${atomicState.learnedRate ?: "—"} ac/h")}
${sl("Week hours", "mow ${rnd(atomicState.mowHoursWeek ?: 0, 1)} h · return ${rnd(atomicState.returnHoursWeek ?: 0, 1)} h · charge ${rnd(atomicState.chargeHoursWeek ?: 0, 1)} h · idle ${rnd(atomicState.dockIdleHoursWeek ?: 0, 1)} h · service ${rnd(atomicState.serviceHoursWeek ?: 0, 1)} h", "", "")}
${sl("Finished maps", jobMowHistText(), "", "")}
${sl("Since install", "mow ${rnd(atomicState.mowHoursLife ?: 0, 1)} · return ${rnd(atomicState.returnHoursLife ?: 0, 1)} · charge ${rnd(atomicState.chargeHoursLife ?: 0, 1)} · idle ${rnd(atomicState.dockIdleHoursLife ?: 0, 1)} · service ${rnd(atomicState.serviceHoursLife ?: 0, 1)}", "", "")}
"""
}

def loadBladeCurves() {
    def raw = atomicState.bladeCurvesJson
    if (!raw) return []
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(raw as String)
        return (parsed instanceof List) ? parsed : []
    } catch (ignored) {
        return []
    }
}

def saveBladeCurves(list) {
    try {
        atomicState.bladeCurvesJson = groovy.json.JsonOutput.toJson(list)
    } catch (ignored) {}
}

def bladeSampleRow(h, kind, obsAcres = null) {
    return [
        h      : h,
        slice  : atomicState.lastMowSliceH ?: atomicState.avgMowSliceH,
        drain  : atomicState.mowPctPerHour,
        acres  : (obsAcres != null) ? obsAcres : atomicState.learnedRate,
        kind   : kind ?: "slice",
        temp   : atomicState.tempF,
        growth : atomicState.growthScore
    ]
}

def startBladeCurve() {
    closeBladePeriod()
    atomicState.bladePeriodStartMs = now()
    def list = loadBladeCurves()
    def nid = ((atomicState.bladeCurveId ?: 0) as int) + 1
    atomicState.bladeCurveId = nid
    atomicState.bladeMowH = 0
    def snap = bladeSampleRow(0.0d, "replace", atomicState.learnedRate)
    list << [id: nid, startMs: now(), samples: [snap]]
    if (list.size() > 12) list = list[(list.size() - 12)..(list.size() - 1)]
    saveBladeCurves(list)
}

def noteBladeWearSample(kind, obsAcres = null) {
    def list = loadBladeCurves()
    if (!list) return
    def cur = list[-1]
    if (!(cur instanceof Map)) return
    def samples = cur.samples
    if (!(samples instanceof List)) samples = []
    def h = 0.0d
    try { h = rnd((atomicState.bladeMowH ?: 0) as double, 2) } catch (ignored) { h = 0.0d }
    if (samples && kind != "replace") {
        def last = samples[-1]
        try {
            if (last instanceof Map && last.kind == kind && Math.abs(((last.h ?: 0) as double) - h) < 0.05d) return
        } catch (ignored) {}
    }
    if (samples.size() >= 300) return
    samples << bladeSampleRow(h, kind ?: "slice", obsAcres)
    cur.samples = samples
    list[-1] = cur
    saveBladeCurves(list)
}

def bladeCurveLines() {
    def list = loadBladeCurves()
    def n = list?.size() ?: 0
    def id = atomicState.bladeCurveId ?: "—"
    def lines = []
    lines << sl("Curves", "${n} stored · current ${id}", "", "New curve on Blades replaced. Old curves stay.")
    if (!list) {
        lines << sl("Samples", "none yet", "", "Blades replaced starts curve 1. Slices before that are ignored.")
        return lines
    }
    list.each { c ->
        if (!(c instanceof Map)) return
        def cid = c.id ?: "?"
        def samples = c.samples
        def ns = (samples instanceof List) ? samples.size() : 0
        lines << sl("Curve ${cid}", "${ns} samples · ${msClock(c.startMs)}", "", "h = mow hours since that replace.")
        if (samples instanceof List) {
            samples.each { s ->
                if (!(s instanceof Map)) return
                lines << "· ${s.h}h  ${s.kind}  slice ${s.slice ?: "—"}h  drain ${s.drain ?: "—"}%/h  ${s.acres ?: "—"} ac/h  ${s.temp ?: "—"}F  g ${s.growth ?: "—"}"
            }
        }
    }
    return lines
}

def detailGrowth() {
    def leafW = 0
    def soilW = 0
    try { leafW = (atomicState.prevLeafWetFrac ?: 0) as double } catch (ignored) { leafW = 0 }
    try { soilW = (atomicState.prevSoilWetFrac ?: 0) as double } catch (ignored) { soilW = 0 }
    def leafOn = leafW > 0.01d
    def soilOn = soilW > 0.01d
    return """
${sl("TARGET", "${atomicState.growthScore} → ${atomicState.targetPasses} maps", "", "")}
${sl("WHY", atomicState.growthWhy ?: "—", "", "")}
${sl("GDD", "${atomicState.gddWeek ?: 0} this week   today ${atomicState.gddToday ?: "—"}   pace ${atomicState.gddPace ?: "—"}", "", "Base 50 F.")}
${sl("Last week rain", "${d2(lastWeekInches())} in (hub ${d2(atomicState.rainInchesPrevWeek)} · meteo ${d2(atomicState.meteoPrevWeekIn)})", "", "Growth uses last Mon–Sun.")}
${sl("Rain hours", "${d2(atomicState.rainHoursWeek)} this week / ${d2(atomicState.rainHoursPrevWeek)} last", "", "Lost mow time.")}
${sl("Weather debt", "${d2(atomicState.debtCarryH ?: atomicState.weatherDebtH)}h", "", "Unpaid wet hours. Adds that fraction of a map until the week is on the schedule.")}
${slIf(leafOn, "Leaf wet last week", d2(leafW), "+0.3 to score if over 0.5.")}${slIf(soilOn, "Soil wet last week", d2(soilW), "+0.3 to score if over 0.5. Not a Dry veto.")}"""
}

def detailWx() {
    def stormOn = atomicState.stormStart != null
    def gaugeOn = atomicState.gaugeInches != null || atomicState.gaugeRate != null
    return """
${sl("Sky", atomicState.weatherState ?: "—", "", "")}
${sl("Temp", "${atomicState.tempF ?: "—"} F", "", "")}
${slSub("RH", "${atomicState.humidity ?: "—"}%")}
${sl("Dew", atomicState.dewOn ? "on" : "off", "", "")}
${slSub("dewpoint", "${atomicState.dewF ?: "—"} F")}
${slSub("air spread", spreadVsDewText(atomicState.spreadF))}
${slSub("grass spread", spreadVsDewText(atomicState.grassSpreadF))}
${sl("Rain now", atomicState.raining ? "yes" : "no", "", "")}
${sl("Leaf", "${atomicState.leafPct ?: "—"}% (${atomicState.leafSrc ?: "—"})", "", "")}
${sl("Soil", "${atomicState.soilPct ?: "—"}% (${atomicState.soilSrc ?: "—"})", "", "Growth only.")}
${sl("Wind", "${atomicState.wind ?: "—"} mph", "", "")}
${slSub("clouds", "${atomicState.clouds ?: "—"}%")}
${sl("Rain clear", rainOffText(), "", "Future slot only.")}
${sl("Dew clear", dewClearText(), "", "After next dew, not the current dry hour.")}
${sl("Rain chance today", "${atomicState.forecastRainPct ?: "—"}%", "", "")}
${slSub("late week", "${atomicState.forecastLateWeekPct ?: "—"}%")}
${slIf(stormOn, "Storm", "${msClock(atomicState.stormStart)} · ${d2(atomicState.stormHours)}h · ${atomicState.stormInches ?: "?"} in", "")}${slIf(gaugeOn, "Gauge", "${atomicState.gaugeInches ?: "—"} in · ${atomicState.gaugeRate ?: "—"} in/h", "")}${sl("ET0", "since ${atomicState.et0SinceLastPrecip ?: "—"} · 12h ${atomicState.et0Last12 ?: "—"} · next ${atomicState.et0Next12 ?: "—"}", "", "in / period.")}
${statusForecastLines()}
"""
}

def detailDebug() {
    mowPaceHPerDay()
    return """
${sl("APP", appVersion(), "", "")}
${sl("ERROR", atomicState.lastError ?: "none", "", "")}
${sl("POLL", pollAgeText(), "", "Last successful Hubitat GET.")}
${sl("HA stamp", payloadAgeText(), "", "Entity last_updated age. Not the hours clock.")}
${sl("Last payload", atomicState.lastPayloadState ?: "?", "", "State credited on last poll.")}
${sl("Hours gap", lastGapText(), "", "Last poll interval.")}
${sl("Week clock", "${d2(weekBucketSum())} / ${d2(weekElapsedH())} = ${d2(weekHourRatio())} (off ${d2(weekHourOff())})", "", "Week buckets vs elapsed. Target 1.0.")}
${sl("Mow pace", atomicState.mowPaceNote ?: "—", "", "Date uses life/days. Week line is only a check.")}
${sl("HA raw", atomicState.haRaw ?: "?", "", "Last entity snapshot.")}
${sl("Last HA ok", msClock(atomicState.lastHaOk), "", "Last successful poll.")}
${sl("HA fails", atomicState.haFails ?: 0, "", "Consecutive failed polls.")}
${sl("Cfg rejects", atomicState.cfgRejects ?: "none", "", "Out-of-range settings ignored.")}
"""
}

def configPage() {
    sanitizeCfg()
    dynamicPage(name: "configPage", title: "Config") {
        section("<b>Home Assistant</b>") {
            input "haUrl", "text", title: "HA base URL", description: "http://192.168.x.x:8123", required: true
            input "haToken", "password", title: "Long-lived access token", required: true
            input "mowerEntity", "text", title: "Mower entity id", defaultValue: "lawn_mower.navimow_x450", required: true
        }
        section("<b>Hubitat devices</b>") {
            input "wxDev", "capability.temperatureMeasurement", title: "Weather station", required: true
            input "leafDev", "capability.sensor", title: "Leaf wetness (optional)", required: false
            input "rainGaugeDev", "capability.sensor", title: "Rain gauge (optional)", required: false
            input "soilDev", "capability.sensor", title: "Soil moisture (optional)", required: false
        }
        section("<b>Lawn</b>") {
            input "lawnAcres", "decimal", title: "Mapped acres", required: true, defaultValue: 1.00
            input "bladeHours", "number", title: "Blade life hours", defaultValue: 160, required: false
            paragraph "Mow hours between blade changes. Reset on the main page."
        }
        section("<b>Behavior</b>") {
            if (atomicState.cfgRejects && atomicState.cfgRejects != "none") {
                paragraph "Rejected: ${atomicState.cfgRejects}"
            }
            input "cfgCatchUpUntilDew", "bool", title: "Catch-up after last light until dew", defaultValue: false
            paragraph "Off (default): first light to last light only, late slice in the last 2 h. On + behind: cut until dew. On + on pace: last light again."
            if (leafDev) {
                input "cfgLeafWetPct", "number", title: "Leaf wet %", defaultValue: 35, required: false
                paragraph "Allowed: 5–90, above leaf dry. Default: 35. Sensor treated as wet."
                input "cfgLeafDryPct", "number", title: "Leaf dry %", defaultValue: 20, required: false
                paragraph "Allowed: 0–80, below leaf wet. Default: 20. Dry enough to cut."
            } else {
                paragraph "Leaf wet/dry 35/20. Boxes appear when a leaf device is selected above."
            }
            input "debugLog", "bool", title: "Debug logging", defaultValue: false
        }
        section("<b>APP</b>") {
            paragraph "VERSION: ${appVersion()}"
        }
    }
}

def installed() { initialize() }

def dropDeadSettings() {
    ["batteryEntity", "calPctFirst", "calPctSecond", "coverageNow", "dropFirstJob", "cfgMinBattery", "cfgPaceLeadPasses", "cfgLostDayInches", "cfgSoilWetPct", "cfgBaseRainLockH", "cfgRainHoursFor3x", "serviceEveryH", "cfgBackfillLo", "cfgBackfillHi", "cfgMowSliceH", "cfgAfterSunsetMin", "cfgFrostF", "cfgAcresPerHour", "cfgStaleMin", "cfgDewWetF", "cfgDewDryF", "cfgForecastRainPct", "cfgLateArriveBuf", "cfgLateMinLightH", "cfgLightWindowOnly"].each { nm ->
        try { app.removeSetting(nm) } catch (ignored) {}
    }
}

def updated() {
    sanitizeCfg()
    dropDeadSettings()
    if (replaceBlades == true) {
        if (serviceHold == true) {
            startBladeCurve()
            log.info "NavimowHA: Blade hours reset, curve " + (atomicState.bladeCurveId ?: "?")
        } else {
            log.info "NavimowHA: Blade reset ignored, service hold off"
        }
        try { app.updateSetting("replaceBlades", [type: "bool", value: false]) } catch (ignored) {}
    }
    if (undoLastCal == true) {
        def typed = null
        try { if (calPctNow != null) typed = calPctNow as double } catch (ignored) {}
        restoreCalBeforeLast()
        try { app.updateSetting("undoLastCal", [type: "bool", value: false]) } catch (ignored) {}
        if (typed == null) atomicState.skipCalApply = true
    }
    if (resetMap == true) {
        markNewJob()
        atomicState.fullPackSinceMs = null
        atomicState.doneSitH = null
        log.info "NavimowHA: Reset map to 0%, open hours not stored"
        try { app.updateSetting("resetMap", [type: "bool", value: false]) } catch (ignored) {}
    }
    def nowHold = (serviceHold == true)
    def wasHold = (atomicState.serviceHoldWas == true)
    if (nowHold && !wasHold) {
        atomicState.chargeTick = null
        atomicState.chargeBatt = null
        atomicState.mowTick = null
        atomicState.mowBatt = null
        atomicState.dockWho = "gate"
    }
    if (!nowHold && wasHold) {
        atomicState.serviceMowH = 0
        atomicState.servicePeriodStartMs = now()
        atomicState.chargeTick = now()
        atomicState.chargeBatt = atomicState.battery
        atomicState.lastPayloadMs = now()
        atomicState.lastPayloadState = atomicState.mowerState
        atomicState.lastGapKind = "service"
    }
    atomicState.serviceHoldWas = nowHold
    if (debugLog == true) log.info "NavimowHA: debug logging on"
    log.info "NavimowHA: app " + appVersion()
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    sanitizeCfg()
    dropPartialLastJob()
    atomicState.dewOn = atomicState.dewOn ?: false
    atomicState.mowHoursWeek = atomicState.mowHoursWeek ?: 0
    atomicState.dockHoursWeek = atomicState.dockHoursWeek ?: 0
    atomicState.chargeHoursWeek = atomicState.chargeHoursWeek ?: 0
    atomicState.dockIdleHoursWeek = atomicState.dockIdleHoursWeek ?: 0
    atomicState.serviceHoursWeek = atomicState.serviceHoursWeek ?: 0
    atomicState.mowHoursLife = atomicState.mowHoursLife ?: 0
    atomicState.returnHoursLife = atomicState.returnHoursLife ?: 0
    atomicState.chargeHoursLife = atomicState.chargeHoursLife ?: 0
    atomicState.dockIdleHoursLife = atomicState.dockIdleHoursLife ?: 0
    atomicState.serviceHoursLife = atomicState.serviceHoursLife ?: 0
    atomicState.serviceMowH = atomicState.serviceMowH ?: 0
    atomicState.bladeMowH = atomicState.bladeMowH ?: 0
    if ((atomicState.lifeHoursSeeded ?: 0) as int != 1) {
        atomicState.mowHoursLife = ((atomicState.mowHoursLife ?: 0) as double) + ((atomicState.mowHoursWeek ?: 0) as double)
        atomicState.returnHoursLife = ((atomicState.returnHoursLife ?: 0) as double) + ((atomicState.returnHoursWeek ?: 0) as double)
        atomicState.chargeHoursLife = ((atomicState.chargeHoursLife ?: 0) as double) + ((atomicState.chargeHoursWeek ?: 0) as double)
        atomicState.dockIdleHoursLife = ((atomicState.dockIdleHoursLife ?: 0) as double) + ((atomicState.dockIdleHoursWeek ?: 0) as double)
        atomicState.serviceHoursLife = ((atomicState.serviceHoursLife ?: 0) as double) + ((atomicState.serviceHoursWeek ?: 0) as double)
        atomicState.lifeHoursSeeded = 1
    }
    atomicState.rainHoursWeek = atomicState.rainHoursWeek ?: 0
    atomicState.rainHoursPrevWeek = atomicState.rainHoursPrevWeek ?: 0
    atomicState.weekStamp = atomicState.weekStamp ?: weekStamp()
    atomicState.lastMowTick = now()
    atomicState.stormStart = atomicState.stormStart ?: 0
    atomicState.lastRain = atomicState.lastRain ?: 0
    atomicState.stormHours = atomicState.stormHours ?: 0
    atomicState.tempSum = atomicState.tempSum ?: 0
    atomicState.tempN = atomicState.tempN ?: 0
    if (atomicState.jobHoursBase == null) atomicState.jobHoursBase = atomicState.mowHoursWeek ?: 0
    if (atomicState.avgLeaveBatt == null) atomicState.avgLeaveBatt = 80
    if (atomicState.avgReturnBatt == null) atomicState.avgReturnBatt = 15
    if (atomicState.firstLiveMs == null) atomicState.firstLiveMs = now()
    if (atomicState.bladePeriodStartMs == null) {
        def list = loadBladeCurves()
        def start = 0
        if (list) {
            def lastC = list[-1]
            if (lastC instanceof Map && lastC.startMs) {
                try { start = lastC.startMs as long } catch (ignored) { start = 0 }
            }
        }
        atomicState.bladePeriodStartMs = start > 0 ? start : atomicState.firstLiveMs
    }

    if (wxDev) {
        subscribe(wxDev, "temperature", wxEvent)
        subscribe(wxDev, "humidity", wxEvent)
        subscribe(wxDev, "dewpoint", wxEvent)
        subscribe(wxDev, "dewPoint", wxEvent)
        subscribe(wxDev, "weather", wxEvent)
        subscribe(wxDev, "condition", wxEvent)
        subscribe(wxDev, "forecast", wxEvent)
        subscribe(wxDev, "windSpeed", wxEvent)
        subscribe(wxDev, "wind", wxEvent)
        subscribe(wxDev, "cloudiness", wxEvent)
        subscribe(wxDev, "cloudCover", wxEvent)
        subscribe(wxDev, "precipRate", wxEvent)
        subscribe(wxDev, "rainRate", wxEvent)
        subscribe(wxDev, "precipToday", wxEvent)
        subscribe(wxDev, "precipLast24", wxEvent)
        subscribe(wxDev, "lastPrecipAt", wxEvent)
        subscribe(wxDev, "et0Today", wxEvent)
        subscribe(wxDev, "et0Next12", wxEvent)
        subscribe(wxDev, "et0Last12", wxEvent)
        subscribe(wxDev, "et0SinceLastPrecip", wxEvent)
        subscribe(wxDev, "lastStormInches", wxEvent)
        subscribe(wxDev, "weatherCode", wxEvent)
        subscribe(wxDev, "chanceOfRain", wxEvent)
        subscribe(wxDev, "precipChance", wxEvent)
        subscribe(wxDev, "weatherTomorrow", wxEvent)
        subscribe(wxDev, "precipitationProbabilityTomorrow", wxEvent)
        subscribe(wxDev, "precipitationProbabilityNext6h", wxEvent)
        subscribe(wxDev, "precipitationProbabilityNext72h", wxEvent)
    }
    if (leafDev) {
        subscribe(leafDev, "humidity", wxEvent)
        subscribe(leafDev, "leafWetness", wxEvent)
        subscribe(leafDev, "wetness", wxEvent)
        subscribe(leafDev, "leaf_wetness", wxEvent)
    }
    if (rainGaugeDev) {
        subscribe(rainGaugeDev, "rain", wxEvent)
        subscribe(rainGaugeDev, "precipitation", wxEvent)
        subscribe(rainGaugeDev, "rainRate", wxEvent)
        subscribe(rainGaugeDev, "precipRate", wxEvent)
        subscribe(rainGaugeDev, "rainDaily", wxEvent)
    }
    if (soilDev) {
        subscribe(soilDev, "humidity", wxEvent)
        subscribe(soilDev, "moisture", wxEvent)
        subscribe(soilDev, "soilMoisture", wxEvent)
        subscribe(soilDev, "soil_moisture", wxEvent)
    }

    applyCoverage()
    ensureChild()
    if (atomicState.debtCarryH == null) atomicState.debtCarryH = atomicState.weatherDebtH ?: 0
    readHubitatWeather()
    unschedule()
    pollMower()
    runEvery1Minute("pollMower")
}

def ensureChild() {
    def dni = "navimow-ha-gate-status"
    def d = getChildDevice(dni)
    if (!d) {
        d = addChildDevice("brian.navimow", "Navimow HA Gate Status", dni, [name: "Navimow Weekly Cycles", label: "Navimow Weekly Cycles", isComponent: true])
    }
    return d
}

def childReplaceBlades() {
    if (serviceHold != true) {
        log.info "NavimowHA: Blade reset ignored, service hold off"
        return
    }
    startBladeCurve()
    log.info "NavimowHA: Blade hours reset, curve " + (atomicState.bladeCurveId ?: "?")
    try { publishStatus() } catch (ignored) {}
}

def childEvt(d, String name, value) {
    if (!d || name == null) return
    def cur
    try { cur = d.currentValue(name) } catch (ignored) { cur = null }
    if (cur != null && "${cur}" == "${value}") return
    d.sendEvent(name: name, value: value)
}

def publishStatus() {
    def d = ensureChild()
    if (!d) return
    childEvt(d, "shouldRun", atomicState.shouldRun ? "yes" : "no")
    childEvt(d, "lawnDry", atomicState.lawnDry ? "dry" : "wet")
    childEvt(d, "inWindow", atomicState.inWindow ? "yes" : "no")
    childEvt(d, "rainLock", atomicState.rainLock ? "yes" : "no")
    childEvt(d, "waitHours", atomicState.waitHours)
    def rainAt = hourlyRainClearMs()
    childEvt(d, "rainClearAt", rainAt ? fmtClock(rainAt) : "none")
    childEvt(d, "mowerState", atomicState.mowerState ?: "")
    childEvt(d, "battery", atomicState.battery)
    childEvt(d, "dockWho", atomicState.dockWho ?: "")
    childEvt(d, "estJobPct", atomicState.estJobPct)
    childEvt(d, "calPct", atomicState.calPct)
    childEvt(d, "haMowProgress", atomicState.haMowProgressLive)
    childEvt(d, "haMowProgressSnap", atomicState.haMowProgressSnap)
    childEvt(d, "doneBand", doneBandText())
    childEvt(d, "cyclesText", "" + atomicState.passesDone + " of " + atomicState.targetPasses)
    childEvt(d, "nextStart", nextStartText())
    nextEndText()
    childEvt(d, "nextMow", nextMowText())
    childEvt(d, "nextDock", nextDockText())
    childEvt(d, "cycleFinish", cycleFinishText())
    childEvt(d, "mowAhead", atomicState.mowAhead ? "yes" : "no")
    childEvt(d, "catchUp", atomicState.catchUp ? "yes" : "no")
    childEvt(d, "todayPrecious", atomicState.todayPrecious ? "yes" : "no")
    childEvt(d, "holdReason", holdReason())
    childEvt(d, "serviceHold", (serviceHold == true) ? "yes" : "no")
    def svcEvery = serviceEveryHours()
    def svcUsed = (atomicState.serviceMowH ?: 0) as double
    def svcLeft = svcEvery - svcUsed
    if (svcLeft < 0) svcLeft = 0
    childEvt(d, "serviceLeftH", rnd(svcLeft, 2))
    def bEvery = bladeLifeHours()
    def bUsed = (atomicState.bladeMowH ?: 0) as double
    def bLeft = bEvery - bUsed
    if (bLeft < 0) bLeft = 0
    childEvt(d, "bladeLeftH", rnd(bLeft, 2))
    childEvt(d, "raining", atomicState.raining ? "yes" : "no")
    childEvt(d, "leafPct", atomicState.leafPct)
    childEvt(d, "hoursToFull", atomicState.hoursToFull)
    childEvt(d, "mowHoursLeft", atomicState.mowHoursLeft)
    childEvt(d, "lastMowStart", edgeClock(atomicState.lastMowStartMs))
    childEvt(d, "firstSliceStart", edgeClock(firstTodayMs()))
    childEvt(d, "lastMowDock", edgeClock(atomicState.lastMowDockMs))
    childEvt(d, "haStale", haStale() ? "yes" : "no")
    childEvt(d, "mqttStale", mqttStale() ? "yes" : "no")
    childEvt(d, "haError", atomicState.lastError ?: "ok")
    childEvt(d, "appVersion", appVersion())
}

def noteSpread(spread) {
    if (spread == null) return
    def row = "" + now() + ":" + spread
    def prev = atomicState.spreadHist ?: ""
    def parts = prev ? prev.tokenize("|") : []
    parts << row
    while (parts.size() > 6) parts.remove(0)
    atomicState.spreadHist = parts.join("|")
}

def trendDewEta(t, need) {
    def prev = atomicState.spreadHist ?: ""
    def parts = prev ? prev.tokenize("|") : []
    if (parts.size() < 2) return null
    def a = parts[parts.size() - 2].tokenize(":")
    def b = parts[parts.size() - 1].tokenize(":")
    if (a.size() < 2 || b.size() < 2) return null
    def t0 = a[0] as long
    def t1 = b[0] as long
    def s0 = toNum(a[1])
    def s1 = toNum(b[1])
    if (s0 == null || s1 == null || t1 <= t0) return null
    def slope = ((s1 as double) - (s0 as double)) / ((t1 - t0) / 3600000.0)
    if (slope < 0.15) return null
    return t + (((need as double) / slope) * 3600000L) as long
}

def parseWxIsoMs(raw) {
    if (!raw || raw == "none") return null
    def s = "${raw}".trim()
    if (s.length() == 16) s = s + ":00"
    if (s.length() > 19) s = s.substring(0, 19)
    try {
        def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        try { sdf.setTimeZone(location.timeZone) } catch (ignored) {}
        def d = sdf.parse(s)
        if (d) return d.time
    } catch (ignored) {}
    try { return toDateTime(s).time } catch (ignored) {}
    return null
}

def hourlyDewEta() {
    if (!wxDev) return null
    return parseWxIsoMs(attr(wxDev, ["dewClearAt"]))
}

def eveningDewOnMs() {
    def wet = 3.0d
    def dry = 5.0d
    try { wet = cfg("dewWetF") as double } catch (ignored) {}
    try { dry = cfg("dewDryF") as double } catch (ignored) {}
    def raw = wxDev ? attr(wxDev, ["dewSpreads"]) : null
    if (!raw) return null
    def parts = "${raw}".split(",")
    def i = 0
    if (atomicState.dewOn == true) {
        while (i < parts.size()) {
            def sp
            try { sp = parts[i].trim() as double } catch (ignored) { sp = null }
            if (sp != null && sp >= dry) break
            i++
        }
    }
    while (i < parts.size()) {
        def sp
        try { sp = parts[i].trim() as double } catch (ignored) { sp = null }
        if (sp != null && sp <= wet) {
            def hr = Calendar.getInstance(location.timeZone)
            hr.setTimeInMillis(now())
            hr.add(Calendar.HOUR_OF_DAY, i)
            hr.set(Calendar.MINUTE, 0)
            hr.set(Calendar.SECOND, 0)
            def ms = hr.getTimeInMillis()
            if ((ms as long) > now()) return ms
        }
        i++
    }
    return null
}

def sunOn(dayOff) {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    cal.setTimeInMillis(now())
    cal.add(Calendar.DATE, dayOff as int)
    try { return getSunriseAndSunset(dateValue: cal.getTime()) }
    catch (ignored) { return getSunriseAndSunset() }
}

def cycleDayStartMs(first, last) {
    def start = first as long
    def end = last as long
    def dew = dewClearOnOrAfterMs(start)
    if (dew != null && (dew as long) > start && (dew as long) < end) return dew as long
    def guess = start + ((dewWaitMin() as int) * 60000L)
    if (guess < end) return guess
    return start
}

def cycleDayLastMs(ss, dayOff) {
    def pad = (afterSunsetMin() as int) * 60000L
    def sun = ((dayOff as int) == 0) ? ss : sunOn(dayOff)
    def last = sun.sunset.time + pad
    if (lightWindowOnly() || atomicState.catchUp != true) return last
    if ((dayOff as int) == 0) {
        def dew = eveningDewOnMs()
        if (dew != null && (dew as long) > now()) return dew
    }
    return last
}

def hourlyRainClearMs() {
    if (!wxDev) return null
    return parseWxIsoMs(attr(wxDev, ["rainClearAt"]))
}

def dewEtaMs(ss, t) {
    def floor = ss.sunrise.time + (dewWaitMin() * 60000L)
    def dry = cfg("dewDryF") as double
    def need = dry
    def useSp = atomicState.grassSpreadF
    if (useSp == null) useSp = atomicState.spreadF
    if (useSp != null) need = dry - (useSp as double)
    if (need <= 0) return t
    def hourly = hourlyDewEta()
    def trend = trendDewEta(t, need)
    def guess = t + ((need / 1.6) * 3600000L)
    def eta = floor
    if (hourly != null && hourly > t) eta = lmax(eta, hourly)
    else if (trend != null) eta = lmax(eta, trend)
    else eta = lmax(eta, guess)
    return eta as long
}

def solarWm2() {
    def s = toNum(attr(wxDev, ["solarRadiation", "shortwaveRadiation", "irradiance"]))
    if (s != null) return s as double
    def lux = toNum(attr(wxDev, ["illuminance"]))
    if (lux != null) return ((lux as double) / 120.0)
    def uv = toNum(attr(wxDev, ["ultravioletIndex", "uvIndex"]))
    if (uv != null) return (uv as double) * 80.0
    return null
}

def grassOffsetF() {
    def clouds = atomicState.clouds
    def wind = atomicState.wind
    def c = (clouds != null) ? (clouds as double) : 40.0d
    def w = (wind != null) ? (wind as double) : 4.0d
    def clear = dmax(0.0d, (100.0d - c) / 100.0d)
    def windCut = 1.0d
    if (w >= 12) windCut = 0.35d
    else if (w >= 8) windCut = 0.55d
    else if (w >= 5) windCut = 0.75d
    def nightOff = (2.0d + (5.0d * clear)) * windCut
    def afterRiseH = 0.0d
    def isNight = true
    try {
        def ss = getSunriseAndSunset()
        def n = now()
        isNight = (n < ss.sunrise.time || n > ss.sunset.time)
        if (!isNight) afterRiseH = (n - ss.sunrise.time) / 3600000.0d
    } catch (ignored) {}
    if (isNight) return rnd(dmin(8.0d, nightOff), 1)
    def solar = solarWm2()
    def sunCut = 1.0d
    if (solar != null) {
        if ((solar as double) >= 250) sunCut = 0.15d
        else if ((solar as double) >= 120) sunCut = 0.35d
        else if ((solar as double) >= 40) sunCut = 0.65d
    } else if (afterRiseH >= 3) sunCut = 0.2d
    else if (afterRiseH >= 2) sunCut = 0.45d
    else if (afterRiseH >= 1) sunCut = 0.7d
    return rnd(dmin(8.0d, nightOff * sunCut), 1)
}

def grassSpreadF() {
    if (atomicState.spreadF == null) return null
    def off = 0.0d
    try { off = (atomicState.grassOffF ?: grassOffsetF()) as double } catch (ignored) { off = 0.0d }
    return rnd((atomicState.spreadF as double) - off, 1)
}

def hubMonth() {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    return cal.get(Calendar.MONTH) + 1
}

def dewWaitMin() {
    def m = hubMonth()
    if (m in [6, 7, 8]) return 90
    if (m in [4, 5, 9]) return 150
    return 180
}

def afterSunsetMin() {
    def m = hubMonth()
    if (m in [6, 7, 8]) return 40
    if (m in [4, 5, 9]) return 30
    return 20
}

def firstLightMs() {
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def first = ss.sunrise.time - pad
    def last = ss.sunset.time + pad
    if (now() >= last) first = sunOn(1).sunrise.time - pad
    return first
}

def rainOffText() {
    if (!(atomicState.raining == true) && !(atomicState.rainLock == true)) return "dry"
    def ms = hourlyRainClearMs()
    if (ms && ms > now()) return fmtClock(ms)
    if (atomicState.waitHours) return "film ${d2(atomicState.waitHours)} h"
    return "wet"
}

def spreadVsDewText(sp) {
    if (sp == null) return "—"
    def n
    try { n = sp as double } catch (ignored) { return "${sp}" }
    def wet = 3.0d
    def dry = 5.0d
    try { wet = cfg("dewWetF") as double } catch (ignored) {}
    try { dry = cfg("dewDryF") as double } catch (ignored) {}
    if (n <= wet) return "${n} F ≤ ${wet} wet"
    if (n >= dry) return "${n} F ≥ ${dry} dry"
    return "${n} F  ${wet} < spread < ${dry}"
}

def dewOnText() {
    return (atomicState.dewOn == true) ? "on" : "off"
}

def nextDewText() {
    if (atomicState.dewOn == true) return "already on"
    def eve = eveningDewOnMs()
    return eve ? fmtClock(eve) : "none in forecast"
}

def dewSpreadsList() {
    def raw = wxDev ? attr(wxDev, ["dewSpreads"]) : null
    def out = []
    if (!raw) return out
    "${raw}".split(",").each { p ->
        try { out << ("${p}".trim() as double) } catch (ignored) { out << null }
    }
    return out
}

def hourFromNowMs(i) {
    def hr = Calendar.getInstance(location.timeZone)
    hr.setTimeInMillis(now())
    hr.add(Calendar.HOUR_OF_DAY, i as int)
    hr.set(Calendar.MINUTE, 0)
    hr.set(Calendar.SECOND, 0)
    return hr.getTimeInMillis()
}

def nextDewClearMs() {
    def dry = 5.0d
    def wet = 3.0d
    try { dry = cfg("dewDryF") as double } catch (ignored) {}
    try { wet = cfg("dewWetF") as double } catch (ignored) {}
    def parts = dewSpreadsList()
    if (!parts) return parseWxIsoMs(wxDev ? attr(wxDev, ["dewClearAt"]) : null)
    def i = 0
    if (atomicState.dewOn != true) {
        while (i < parts.size()) {
            def sp = parts[i]
            if (sp != null && sp <= wet) break
            i++
        }
        if (i >= parts.size()) return null
        i++
    }
    while (i < parts.size()) {
        def sp = parts[i]
        def h = hourFromNowMs(i)
        if (sp != null && sp >= dry && (h as long) > now()) return h
        i++
    }
    return null
}

def dewClearOnOrAfterMs(fromMs) {
    if (fromMs == null) return null
    def dry = 5.0d
    try { dry = cfg("dewDryF") as double } catch (ignored) {}
    def parts = dewSpreadsList()
    if (!parts) {
        def at = parseWxIsoMs(wxDev ? attr(wxDev, ["dewClearAt"]) : null)
        if (at != null && (at as long) >= (fromMs as long)) return at as long
        return null
    }
    def i = 0
    while (i < parts.size()) {
        def h = hourFromNowMs(i)
        if ((h as long) + 3600000L <= (fromMs as long)) {
            i++
            continue
        }
        def sp = parts[i]
        if (sp != null && (sp as double) >= dry) {
            def t = h as long
            if (t < (fromMs as long)) t = fromMs as long
            return t
        }
        i++
    }
    return null
}

def dewClearMs() {
    return nextDewClearMs()
}

def applyMorningHolds(t) {
    def eta = t as long
    def dew = dewClearMs()
    if (dew != null && dew > eta && dew < eta + (36L * 3600000L)) eta = dew
    else {
        def later = dewClearOnOrAfterMs(eta)
        if (later != null && later > eta) eta = later
    }
    return eta
}

def dewClearText() {
    def ms = dewClearMs()
    if (ms && (ms as long) > now()) return fmtClock(ms)
    if (atomicState.dewOn == true) return "still on"
    return "not in forecast after next dew"
}

def fmtClock(ms) {
    return new Date(ms).format("EEE h:mm a", location.timeZone)
}

def firstTodayMs() {
    def t = atomicState.firstSliceStartMs
    if (!t) return null
    def day = atomicState.firstSliceDay
    def today = new Date(now()).format("yyyy-MM-dd", location.timeZone)
    if (day != today) return null
    return t
}

def leaveEtaMs() {
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def first = ss.sunrise.time - pad
    def last = ss.sunset.time + pad
    def t = now()
    def eta
    if (!lightWindowOnly() && atomicState.catchUp == true) {
        eta = t
    } else {
        if (t >= last) first = sunOn(1).sunrise.time - pad
        eta = (t < first) ? first : t
    }
    def rainEnd = hourlyRainClearMs()
    def rainHold = (atomicState.raining == true || atomicState.rainLock == true)
    if (rainHold && atomicState.waitHours) {
        def unlock = now() + ((atomicState.waitHours as double) * 3600000L)
        if (unlock > eta) eta = unlock as long
    } else if (rainHold && rainEnd != null && rainEnd > eta) {
        eta = rainEnd as long
    }
    if (atomicState.dewOn == true || atomicState.lawnDry == false) {
        def clear = dewClearMs()
        if (clear != null && (clear as long) > eta) eta = clear as long
    }
    return eta as long
}

def nextWeekFirstLightMs() {
    def pad = (afterSunsetMin() as int) * 60000L
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    def dow = cal.get(Calendar.DAY_OF_WEEK)
    def add = (dow == Calendar.MONDAY) ? 7 : ((Calendar.MONDAY + 7 - dow) % 7)
    if (add == 0) add = 7
    def sun = sunOn(add)
    def first = sun.sunrise.time - pad
    if ((first as long) <= now()) first = sunOn(add + 7).sunrise.time - pad
    return first as long
}

def nextStartText() {
    def txt
    if (forceDock) txt = "force dock"
    else if (serviceHold == true) txt = "service hold"
    else if (enabled == false) txt = "app off"
    else if (haStale()) txt = "HA stale"
    else if (frostLock()) txt = "frost"
    else if (!(atomicState.catchUp)) txt = fmtClock(nextWeekFirstLightMs()) + " · week reset"
    else {
        def eta = leaveEtaMs()
        if (eta < now() + 60000L) txt = "now"
        else txt = fmtClock(eta)
    }
    atomicState.nextStart = txt
    return txt
}

def nextEndText() {
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def last = ss.sunset.time + pad
    def txt
    if (!lightWindowOnly() && atomicState.catchUp) txt = "catch-up"
    else if (now() >= last) txt = "window closed"
    else txt = fmtClock(last)
    atomicState.nextEnd = txt
    return txt
}

def whoLeaveTag() {
    def w = "${atomicState.dockWho ?: ''}"
    if (w == "charge") return "Navimow"
    return "Hubitat"
}

def chargeWaitMs() {
    def h = atomicState.hoursToFull
    if (h == null) return 0L
    try {
        def n = h as double
        if (n < 0) n = 0
        return ((n * 3600000L) as long)
    } catch (ignored) {
        return 0L
    }
}

def nextMowText() {
    def st = atomicState.mowerState
    def txt
    atomicState.nextMowMs = null
    if (st == "mowing") txt = "mowing"
    else if (forceDock) txt = "force dock"
    else if (serviceHold == true) txt = "service hold"
    else if (enabled == false) txt = "app off"
    else if (haStale()) txt = "HA stale"
    else if (frostLock()) txt = "frost"
    else if (!(atomicState.catchUp)) {
        def eta = nextWeekFirstLightMs() + chargeWaitMs()
        def who = whoLeaveTag()
        atomicState.nextMowMs = eta
        txt = fmtClock(eta) + " · " + who + " · week reset"
    } else {
        def eta = leaveEtaMs() + chargeWaitMs()
        def who = whoLeaveTag()
        atomicState.nextMowMs = eta
        if (eta < now() + 60000L) txt = "now · " + who
        else txt = fmtClock(eta) + " · " + who
    }
    atomicState.nextMow = txt
    return txt
}

def nextDockText() {
    def st = atomicState.mowerState
    if (st == "docked") {
        atomicState.nextDock = "docked"
        return atomicState.nextDock
    }
    if (st == "returning") {
        atomicState.nextDock = "returning · " + d2(returnTripHours()) + "h trip"
        return atomicState.nextDock
    }
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def last = ss.sunset.time + pad
    def packH = 0
    try { packH = (atomicState.mowHoursLeft ?: 0) as double } catch (ignored) { packH = 0 }
    if (packH < 0) packH = 0
    def packAt = now() + ((packH * 3600000L) as long)
    def txt
    if (st != "mowing" && st != "paused" && st != "returning") {
        txt = "?"
    } else if (!lightWindowOnly() && atomicState.catchUp == true) {
        txt = fmtClock(packAt) + " · pack"
    } else if (now() >= last) {
        txt = "last light now"
    } else if (packAt <= last) {
        txt = fmtClock(packAt) + " · pack"
    } else {
        txt = fmtClock(last) + " · last light"
    }
    atomicState.nextDock = txt
    return txt
}

def packMowHours() {
    def drn = mowPctPerHour()
    if (drn == null || drn <= 0) return null
    def h = (leaveTarget() - returnTarget()) / drn
    if (h <= 0) return null
    return h
}

def packChargeHours() {
    def chg = chargePctPerHour()
    if (chg == null || chg <= 0) return null
    def h = (leaveTarget() - returnTarget()) / chg
    if (h <= 0) return null
    return h
}

def returnTripHours() {
    def r = atomicState.avgReturnTripH
    if (r != null) {
        try {
            def n = r as double
            if (n >= 0.005 && n <= 1.5) return n
        } catch (ignored) {}
    }
    return 0
}

def noteReturnTrip(gapH) {
    noteHourAvg("ReturnTripH", gapH, 0.005d, 1.5d)
}

def noteHourAvg(key, gapH, lo, hi) {
    def g = 0
    try { g = gapH as double } catch (ignored) { return }
    if (g < lo || g > hi) return
    def lastK
    def avgK
    if (key == "ReturnTripH") { lastK = "lastReturnTripH"; avgK = "avgReturnTripH" }
    else if (key == "MowSliceH") { lastK = "lastMowSliceH"; avgK = "avgMowSliceH" }
    else if (key == "ChargeH") { lastK = "lastChargeH"; avgK = "avgChargeH" }
    else return
    atomicState[lastK] = rnd(g, 2)
    def avg = atomicState[avgK]
    if (avg == null) atomicState[avgK] = rnd(g, 2)
    else {
        try {
            atomicState[avgK] = rnd(0.6 * g + 0.4 * (avg as double), 2)
        } catch (ignored) {
            atomicState[avgK] = rnd(g, 2)
        }
    }
}

def sliceWhy() {
    if (atomicState._sliceWhyOverride) return atomicState._sliceWhyOverride
    if (atomicState.raining == true || atomicState.rainLock == true) return "rain"
    if (atomicState.dewOn == true) return "dew"
    if (navimowOwnedOuting() || atomicState.dockWho == "charge") return "charge"
    if (atomicState.lateSlice == true || !inWindow()) return "light"
    if (forceDock == true) return "force"
    def est = 0
    try { est = (atomicState.estJobPct ?: 0) as double } catch (ignored) { est = 0 }
    if (estInDoneBand(est)) return "done"
    if (atomicState.catchUp != true) return "cap"
    def hold = "${holdReason() ?: ''}".toLowerCase()
    if (hold.contains("frost")) return "frost"
    if (hold.contains("battery")) return "batt"
    if (hold.contains("stale")) return "stale"
    if (hold.contains("off") || hold.contains("enabled")) return "off"
    if (hold.contains("force")) return "force"
    if (hold) return hold.replaceAll("[^a-z0-9]+", "-").take(12)
    return "hubitat"
}

def sliceHistSum(raw) {
    def sum = 0.0d
    def n = 0
    sliceHistParts(raw).each { p ->
        try {
            sum = sum + ((p.tokenize(":")[0]) as double)
            n++
        } catch (ignored) {}
    }
    return [sum: rnd(sum, 1), n: n]
}

def sliceHistParts(raw) {
    if (!raw) return []
    return raw.tokenize(",").findAll { it }
}

def sliceHistPretty(raw) {
    def parts = sliceHistParts(raw)
    if (!parts) return "none"
    return parts.collect { p ->
        def bits = p.tokenize(":")
        def pct = bits[0]
        def why = (bits.size() > 1) ? bits[1] : "?"
        "${pct}% ${why}"
    }.join(" · ")
}

def noteSlicePct(gapH) {
    def hpp = 0
    try { hpp = hoursPerPass() as double } catch (ignored) { hpp = 0 }
    if (hpp <= 0) return
    def g = 0
    try { g = gapH as double } catch (ignored) { return }
    if (g <= 0) return
    def pct = rnd((g / hpp) * 100.0, 1)
    if (pct < 0) pct = 0
    def why = sliceWhy()
    atomicState.lastSlicePct = pct
    def parts = sliceHistParts(atomicState.slicePctHist)
    parts << "${pct}:${why}"
    atomicState.slicePctHist = parts.join(",")
    def tot = sliceHistSum(atomicState.slicePctHist)
    atomicState.avgSlicePct = (tot.n > 0) ? rnd((tot.sum as double) / (tot.n as double), 1) : pct
    log.info "NavimowHA: Slice ${pct}% ${why} map sum ${tot.sum}%"
}

def slicePctText() {
    def raw = atomicState.slicePctHist ?: ""
    if (!raw) return "none this map"
    def tot = sliceHistSum(raw)
    return sliceHistPretty(raw) + "  sum ${tot.sum}%"
}

def detailSlices() {
    def cur = atomicState.slicePctHist ?: ""
    def last = atomicState.lastMapSliceHist ?: ""
    def c = sliceHistSum(cur)
    def l = sliceHistSum(last)
    return """
${sl("This map", cur ? sliceHistPretty(cur) : "none yet", "", "charge pack · light last light · dew · rain · force · cap week pace · done band · service cut short · frost/batt/stale/off if Hubitat recalled for that hold.")}
${slSub("this sum", "${c.sum}% of map hours  n=${c.n}")}
${sl("Last map", last ? sliceHistPretty(last) : "none", "", "Frozen when the last map closed.")}
${slSub("last sum", last ? "${l.sum}% of map hours  n=${l.n}" : "—")}
"""
}

def noteMowSlice(gapH) {
    noteHourAvg("MowSliceH", gapH, 0.08d, 6.0d)
    noteBladeWearSample("slice")
}

def noteChargeSit(gapH) { noteHourAvg("ChargeH", gapH, 0.10d, 8.0d) }

def navimowOwnedOuting() {
    return atomicState.outingBy == "charge" && atomicState.dockWho == "charge"
}

def leaveReadyPct() {
    def tgt = 80.0d
    try { tgt = leaveTarget() as double } catch (ignored) {}
    def learned = atomicState.leaveBatt
    if (learned == null) learned = atomicState.avgLeaveBatt
    if (learned != null) {
        try {
            def n = learned as double
            if (n < tgt) tgt = n
        } catch (ignored) {}
    }
    return tgt
}

def packAtLeave() {
    def b = null
    try { b = atomicState.battery as double } catch (ignored) { return false }
    if (b == null) return false
    return b >= leaveReadyPct()
}

def estDoneForCycle() {
    def est
    try {
        est = (atomicState.estJobPct != null) ? (atomicState.estJobPct as double) : (estJobPct() as double)
    } catch (ignored) { est = 0 }
    return est >= 100.0
}

def remainingJobHours() {
    def est
    try {
        est = (atomicState.estJobPct != null) ? (atomicState.estJobPct as double) : (estJobPct() as double)
    } catch (ignored) { est = 0 }
    if (est >= 100.0) return 0
    def pass
    try { pass = hoursPerPass() as double } catch (ignored) { return null }
    if (pass == null || pass <= 0) return null
    def rem = ((100.0 - est) / 100.0) * pass
    return rem
}

def cycleFinishText() {
    def rem = remainingJobHours()
    def txt
    atomicState.cycleFinishMs = null
    if (rem == null) txt = "?"
    else if (estDoneForCycle()) txt = "${d2(atomicState.estJobPct)}% est · map open"
    else {
        def ss = getSunriseAndSunset()
        def pad = (afterSunsetMin() as int) * 60000L
        def first = ss.sunrise.time - pad
        def dayOff = 0
        def last = cycleDayLastMs(ss, dayOff)
        def t = now()
        if (t >= last) {
            dayOff = 1
            first = sunOn(1).sunrise.time - pad
            last = cycleDayLastMs(ss, dayOff)
        }
        def leave = leaveEtaMs()
        if (leave > t) t = leave
        if (t < first) t = first
        def dayStart = cycleDayStartMs(first, last)
        if (t < dayStart && dayStart < last) t = dayStart
        if (atomicState.mowerState == "docked") t = t + chargeWaitMs()
        def slices = 0
        def guard = 0
        def packFull = packMowHours()
        def chgH = packChargeHours()
        if (packFull == null || chgH == null) {
            txt = "after a learned pack"
        } else {
        def packH = packFull
        try {
            def left = atomicState.mowHoursLeft
            if (left != null && (left as double) > 0) packH = left as double
        } catch (ignored) {}
        while (rem > 0 && guard < 40 && (dayOff as int) <= 7) {
            guard++
            if (t >= last) {
                dayOff = (dayOff as int) + 1
                first = sunOn(dayOff).sunrise.time - pad
                last = cycleDayLastMs(ss, dayOff)
                t = cycleDayStartMs(first, last)
                continue
            }
            if (t < first) t = first
            def dayStart = cycleDayStartMs(first, last)
            if (t < dayStart && dayStart < last) t = dayStart
            def avail = (last - t) / 3600000.0
            if (avail <= 0) {
                dayOff = (dayOff as int) + 1
                first = sunOn(dayOff).sunrise.time - pad
                last = cycleDayLastMs(ss, dayOff)
                t = cycleDayStartMs(first, last)
                continue
            }
            if (packH <= 0) {
                t = t + ((chgH * 3600000L) as long)
                packH = packFull
                continue
            }
            def cut = rem
            if (cut > packH) cut = packH
            if (cut > avail) cut = avail
            slices++
            t = t + ((cut * 3600000L) as long)
            rem = rem - cut
            packH = packH - cut
            t = t + ((returnTripHours() * 3600000L) as long)
            if (rem > 0 && packH <= 0) {
                t = t + ((chgH * 3600000L) as long)
                packH = packFull
            }
        }
        if (rem > 0 && (dayOff as int) > 7) txt = "beyond 1 week"
        else if (rem > 0) txt = "not scheduled"
        else {
            atomicState.cycleFinishMs = t
            txt = fmtClock(t) + " · " + slices + " slice · " + d2(remainingJobHours()) + "h cut"
            def rh = returnTripHours() * slices
            if (rh > 0.02) txt = txt + " · " + d2(rh) + "h return"
        }
        }
    }
    atomicState.cycleFinish = txt
    return txt
}

def holdReason() {
    if (forceDock) return "force dock"
    if (serviceHold == true) return "service hold"
    if (enabled == false) return "app off"
    if (haStale()) return "HA stale"
    if (frostLock()) return "frost"
    if (atomicState.raining) return "raining"
    if (atomicState.rainLock) return "rain lock " + atomicState.waitHours + "h"
    if (atomicState.lawnDry == false) {
        if (atomicState.dewOn) return "dew"
        return "grass wet"
    }
    if (!lightAllowsWork()) return "outside light window"
    def batt = null
    try { batt = atomicState.battery as double } catch (ignored) {}
    def docked = haIsDocked()
    if (atomicState.chargeHold == true || (docked && batt != null && batt < packFieldFloor())) return "charging"
    if (!docked && batt != null && batt < packFieldFloor()) return "battery low"
    if (newJobHeld()) return "next map at first light"
    if (atomicState.shouldRun) return "ok to mow"
    return "held"
}

def serviceEveryHours() { 80.0d }

def closeBladePeriod() {
    def start = 0
    try { start = atomicState.bladePeriodStartMs as long } catch (ignored) { start = 0 }
    if (start <= 0) return
    def days = (now() - start) / 86400000.0d
    def used = 0
    try { used = (atomicState.bladeMowH ?: 0) as double } catch (ignored) { used = 0 }
    if (days >= 1.0d) {
        atomicState.lastBladePace = rnd(used / days, 2)
        log.info "NavimowHA: Blade period ${d2(used)}h / ${d2(days)}d = ${atomicState.lastBladePace} h/day"
    }
}

def servicePaceHPerDay() {
    def start = 0
    try { start = atomicState.bladePeriodStartMs as long } catch (ignored) { start = 0 }
    if (start <= 0) {
        def list = loadBladeCurves()
        if (list) {
            def lastC = list[-1]
            if (lastC instanceof Map && lastC.startMs) {
                try { start = lastC.startMs as long } catch (ignored) { start = 0 }
            }
        }
        if (start <= 0) {
            try { start = atomicState.firstLiveMs as long } catch (ignored) { start = 0 }
        }
        if (start > 0) atomicState.bladePeriodStartMs = start
    }
    def days = 0
    if (start > 0) days = (now() - start) / 86400000.0d
    def used = 0
    try { used = (atomicState.bladeMowH ?: 0) as double } catch (ignored) { used = 0 }
    def last = null
    try { if (atomicState.lastBladePace != null) last = atomicState.lastBladePace as double } catch (ignored) {}
    def cur = (days > 0.01d) ? (used / days) : null
    if (days < 1.0d) {
        atomicState.servicePaceNote = "blade set ${d2(days)}d old, no date until 1 day · ${d2(used)}h so far · last set ${last != null ? d2(last) : "—"}"
        return null
    }
    atomicState.servicePaceNote = "this blade set ${d2(used)}h / ${d2(days)}d = ${cur != null ? d2(cur) : "—"} · last set ${last != null ? d2(last) : "—"}"
    return cur
}

def mowPaceHPerDay() {
    def life = 0
    try { life = (atomicState.mowHoursLife ?: 0) as double } catch (ignored) { life = 0 }
    def first = 0
    try { first = atomicState.firstLiveMs as long } catch (ignored) { first = 0 }
    def days = 0
    if (first > 0) days = (now() - first) / 86400000.0d
    def week = 0
    try { week = (atomicState.mowHoursWeek ?: 0) as double } catch (ignored) { week = 0 }
    def weekD = weekElapsedH() / 24.0d
    def svc = 0
    try { svc = (atomicState.serviceMowH ?: 0) as double } catch (ignored) { svc = 0 }
    def blade = 0
    try { blade = (atomicState.bladeMowH ?: 0) as double } catch (ignored) { blade = 0 }
    def firstTxt = first > 0 ? fmtClock(first) : "unset"
    def lifePace = (days > 0.01d) ? rnd(life / days, 2) : null
    def weekPace = (weekD > 0.01d) ? rnd(week / weekD, 2) : null
    servicePaceHPerDay()
    atomicState.mowPaceNote = "life ${d2(life)}h / ${d2(days)}d since ${firstTxt} = ${lifePace ?: "—"} · week ${d2(week)}h / ${d2(weekD)}d = ${weekPace ?: "—"} · svc ${d2(svc)}h · blade ${d2(blade)}h · ${atomicState.servicePaceNote ?: ""}"
    if (first > 0 && days > 0.25d) return life / days
    return null
}

def dueWhenText(leftH) {
    def pace = mowPaceHPerDay()
    if (pace == null || pace < 0.05d) return "date after more mow hours"
    def days = (leftH as double) / pace
    if (days < 0) days = 0
    def ms = now() + ((days * 86400000.0d) as long)
    def when = new Date(ms).format("EEE MMM d", location.timeZone)
    return "about ${when} at ${d2(pace)} h/day"
}

def bladeLifeHours() {
    def n = 160
    try { if (bladeHours != null) n = bladeHours as double } catch (ignored) { n = 160 }
    if (n < 40) n = 40
    if (n > 400) n = 400
    return n
}

def bladeCountdownText() {
    def every = bladeLifeHours()
    def used = (atomicState.bladeMowH ?: 0) as double
    def left = every - used
    if (left < 0) left = 0
    if (left <= 0) return "Blades due. ${d2(used)}h mowed since last replace (life ${d2(every)}h)."
    def pace = servicePaceHPerDay()
    if (pace == null || pace < 0.05d) return "Blades in ${d2(left)}h (${d2(used)} / ${d2(every)}). date after a full day in this service period"
    def days = left / pace
    if (days < 0) days = 0
    def when = new Date(now() + ((days * 86400000.0d) as long)).format("EEE MMM d", location.timeZone)
    return "Blades in ${d2(left)}h (${d2(used)} / ${d2(every)}). about ${when} at ${d2(pace)} h/day"
}

def serviceCountdownText() {
    def every = serviceEveryHours()
    def used = (atomicState.serviceMowH ?: 0) as double
    def left = every - used
    if (left < 0) left = 0
    if (serviceHold == true) return "Service hold on. Countdown paused at ${d2(left)}h of ${d2(every)}h."
    if (left <= 0) return "Service due. ${d2(used)}h mowed since last hold off (interval ${d2(every)}h)."
    def pace = servicePaceHPerDay()
    if (pace == null || pace < 0.05d) return "Service in ${d2(left)}h (${d2(used)} / ${d2(every)}). date after a full day in this service period"
    def days = left / pace
    if (days < 0) days = 0
    def when = new Date(now() + ((days * 86400000.0d) as long)).format("EEE MMM d", location.timeZone)
    return "Service in ${d2(left)}h (${d2(used)} / ${d2(every)}). about ${when} at ${d2(pace)} h/day"
}

def weekStamp() {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    cal.setFirstDayOfWeek(Calendar.MONDAY)
    cal.setMinimalDaysInFirstWeek(4)
    def w = cal.get(Calendar.WEEK_OF_YEAR)
    return "" + cal.get(Calendar.YEAR) + "-W" + w
}

def mondayStartMs() {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    cal.setFirstDayOfWeek(Calendar.MONDAY)
    def dow = cal.get(Calendar.DAY_OF_WEEK)
    def back = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY)
    cal.add(Calendar.DAY_OF_MONTH, -back)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.getTimeInMillis()
}

def cfgSpec() {
    return [
        staleMin:        [set: null,                 def: 12,   min: 12,  max: 12],
        acresPerHour:    [set: null,                 def: 0.149, min: 0.08, max: 0.22],
        afterSunsetMin:  [set: null,                 def: 30,   min: 30,   max: 30],
        frostF:          [set: null,                 def: 36,   min: 36,   max: 36],
        dewWetF:         [set: null,                 def: 3.0,  min: 3.0,  max: 3.0],
        dewDryF:         [set: null,                 def: 5.0,  min: 5.0,  max: 5.0],
        leafWetPct:      [set: "cfgLeafWetPct",      def: 35,   min: 5,    max: 90],
        leafDryPct:      [set: "cfgLeafDryPct",      def: 20,   min: 0,    max: 80],
        forecastRainPct: [set: null,                 def: 50,   min: 50,   max: 50],
        leaveMin:        [set: null,                 def: 70,   min: 70,   max: 70],
        leaveMax:        [set: null,                 def: 100,  min: 100,  max: 100],
        leaveSeed:       [set: null,                 def: 80,   min: 80,   max: 80],
        returnMin:       [set: null,                 def: 5,    min: 5,    max: 5],
        returnMax:       [set: null,                 def: 20,   min: 20,   max: 20],
        returnSeed:      [set: null,                 def: 15,   min: 15,   max: 15],
        lateArriveBuf:   [set: null,                 def: 5,    min: 5,    max: 5],
        lateMinLightH:   [set: null,                 def: 0.25, min: 0.25, max: 0.25]
    ]
}

def settingNum(nm) {
    def v
    try { v = settings[nm] } catch (ignored) {}
    if (v == null) {
        try { v = this."${nm}" } catch (ignored) {}
    }
    if (v == null || "${v}".trim() == "") return null
    try { return v as double } catch (ignored) { return null }
}

def cfgRaw(name) {
    def spec = cfgSpec()[name]
    if (!spec) return null
    if (!spec.set) return spec.def
    def raw = settingNum(spec.set)
    if (raw == null) return spec.def
    if (raw < spec.min || raw > spec.max) return spec.def
    return rnd(raw, 2)
}

def cfg(name) {
    def v = cfgRaw(name)
    if (name == "dewDryF" && (v as double) <= (cfgRaw("dewWetF") as double)) return cfgSpec().dewDryF.def
    if (name == "dewWetF" && (cfgRaw("dewDryF") as double) <= (v as double)) return cfgSpec().dewWetF.def
    if (name == "leafDryPct" && (v as double) >= (cfgRaw("leafWetPct") as double)) return cfgSpec().leafDryPct.def
    if (name == "leafWetPct" && (cfgRaw("leafDryPct") as double) >= (v as double)) return cfgSpec().leafWetPct.def
    if (name == "leaveMax" && (v as double) <= (cfgRaw("leaveMin") as double)) return cfgSpec().leaveMax.def
    if (name == "leaveMin" && (cfgRaw("leaveMax") as double) <= (v as double)) return cfgSpec().leaveMin.def
    if (name == "returnMax" && (v as double) <= (cfgRaw("returnMin") as double)) return cfgSpec().returnMax.def
    if (name == "returnMin" && (cfgRaw("returnMax") as double) <= (v as double)) return cfgSpec().returnMin.def
    if (name == "leaveSeed") {
        def lo = cfgRaw("leaveMin") as double
        def hi = cfgRaw("leaveMax") as double
        if ((v as double) < lo || (v as double) > hi) return cfgSpec().leaveSeed.def
    }
    if (name == "returnSeed") {
        def lo = cfgRaw("returnMin") as double
        def hi = cfgRaw("returnMax") as double
        if ((v as double) < lo || (v as double) > hi) return cfgSpec().returnSeed.def
    }
    if (name == "acresPerHour" && (v as double) <= 0) return cfgSpec().acresPerHour.def
    return v
}

def sanitizeCfg() {
    def notes = []
    try {
        if (lawnAcres != null) {
            def a = lawnAcres as double
            if (a < 0.10 || a > 10.00) {
                notes << "mapped acres ${a} ignored, using 1.00"
                a = 1.00
            }
            app.updateSetting("lawnAcres", [type: "decimal", value: rnd(a, 2)])
        }
    } catch (ignored) {}
    cfgSpec().each { name, spec ->
        def raw = settingNum(spec.set)
        if (!spec.set) return
        if (raw == null) return
        if (raw < spec.min || raw > spec.max) {
            notes << "${name} ${raw} ignored, using ${spec.def}"
            try { app.updateSetting(spec.set, [type: "decimal", value: spec.def]) } catch (ignored) {}
        } else {
            try { app.updateSetting(spec.set, [type: "decimal", value: rnd(raw, 2)]) } catch (ignored) {}
        }
    }
    def wet = cfgRaw("dewWetF") as double
    def dry = cfgRaw("dewDryF") as double
    if (dry <= wet) notes << "dew dry must be above dew wet, using defaults"
    def ld = cfgRaw("leafDryPct") as double
    def lw = cfgRaw("leafWetPct") as double
    if (ld >= lw) notes << "leaf dry must be below leaf wet, using defaults"
    atomicState.cfgRejects = notes ? notes.join(" · ") : "none"
}

def rnd(val, places) {
    if (val == null) return null
    def n = val as double
    def f = 1
    def i = 0
    def p = places as int
    while (i < p) { f = f * 10; i = i + 1 }
    return (Math.round(n * f) as double) / f
}

def dmax(a, b) {
    def x = 0.0d
    def y = 0.0d
    try { if (a != null) x = a as double } catch (ignored) {}
    try { if (b != null) y = b as double } catch (ignored) {}
    return (x >= y) ? x : y
}

def dmin(a, b) {
    def x = 0.0d
    def y = 0.0d
    try { if (a != null) x = a as double } catch (ignored) {}
    try { if (b != null) y = b as double } catch (ignored) {}
    return (x <= y) ? x : y
}

def lmax(a, b) {
    def x = 0L
    def y = 0L
    try { if (a != null) x = a as long } catch (ignored) {}
    try { if (b != null) y = b as long } catch (ignored) {}
    return (x >= y) ? x : y
}

def cutRate() {
    def r = atomicState.learnedRate
    if (r != null) {
        try {
            def n = r as double
            if (n >= 0.08 && n <= 0.22) return n
        } catch (ignored) {}
    }
    return 0.149d
}

def hoursPerPass() {
    def acres = (lawnAcres ?: 1.00) as double
    def rate = cutRate()
    if (rate <= 0) return 10.27
    return rnd(acres / rate, 2)
}

def sameCalJob(typed = null) {
    def prev = atomicState.calPct
    if (prev == null) return false
    def p = prev as double
    if (typed != null) {
        def t
        try { t = typed as double } catch (ignored) { t = null }
        if (t != null && t < p) return false
    }
    return true
}

def haIsReturning() {
    def st = "${atomicState.mowerState ?: ''}".toLowerCase()
    return st.contains("return")
}

def haIsDocked() {
    if (haIsReturning()) return false
    def st = "${atomicState.mowerState ?: ''}".toLowerCase()
    return st.contains("dock")
}

def calEntryN() {
    def n = atomicState.calN
    if (n == null) n = 0
    def typed = null
    try { if (calPctNow != null && "${calPctNow}" != "") typed = calPctNow as double } catch (ignored) {}
    if (atomicState.calPct != null && !sameCalJob(typed)) {
        atomicState.calN = 0
        n = 0
    }
    return (n as int) + 1
}

def calOrdinal(n) {
    def i = n as int
    if (i == 11 || i == 12 || i == 13) return "${i}th"
    def m = i % 10
    if (m == 1) return "${i}st"
    if (m == 2) return "${i}nd"
    if (m == 3) return "${i}rd"
    return "${i}th"
}

def calSampleText() {
    if (atomicState.calAutoZero == true && ((atomicState.calN ?: 0) as int) == 0) return "0% (auto)"
    if (atomicState.calPct == null) return "—"
    return "${atomicState.calPct}%"
}

def calHelpText() {
    if (haIsDocked()) return "Mower is docked. Enter mowing progress %, then select Done."
    return "Wait until Hubitat shows docked. Field locked so entries stay stable."
}

def calFieldTitle() {
    if (atomicState.calAutoZero == true && ((atomicState.calN ?: 0) as int) == 0) return "(first manual entry)"
    def n = calEntryN()
    def word
    if (n == 1) word = "first"
    else if (n == 2) word = "second"
    else if (n == 3) word = "third"
    else word = calOrdinal(n)
    return "(${word} entry)"
}

def clearCalSettings() {
    ["calPctNow"].each { nm ->
        try { app.removeSetting(nm) } catch (ignored) {}
        try { app.updateSetting(nm, [type: "decimal", value: ""]) } catch (ignored) {}
    }
}

def saveCalUndo() {
    atomicState.calUndoHas = true
    atomicState.calUndoPct = atomicState.calPct
    atomicState.calUndoHours = atomicState.calHours
    atomicState.calUndoN = atomicState.calN
    atomicState.calUndoBase = atomicState.jobHoursBase
    atomicState.calUndoRate = atomicState.learnedRate
    atomicState.calUndoDoneH = atomicState.lastDoneHours
    atomicState.calUndoAuto = atomicState.calAutoZero
}

def restoreCalBeforeLast() {
    if (atomicState.calUndoHas == true) {
        atomicState.calPct = atomicState.calUndoPct
        atomicState.calHours = atomicState.calUndoHours
        atomicState.calN = atomicState.calUndoN
        atomicState.jobHoursBase = atomicState.calUndoBase
        atomicState.learnedRate = atomicState.calUndoRate
        atomicState.lastDoneHours = atomicState.calUndoDoneH
        atomicState.calAutoZero = (atomicState.calUndoAuto == true)
        atomicState.calUndoHas = false
        refreshEstJobPct()
        log.info "NavimowHA: Restored sample before last, now " + (atomicState.calPct == null ? "none" : atomicState.calPct + "%") + " n=" + (atomicState.calN ?: 0)
        return
    }
    def n = (atomicState.calN ?: 0) as int
    if (n <= 0) {
        log.info "NavimowHA: No last sample to overwrite"
        return
    }
    atomicState.calN = n - 1
    if ((atomicState.calN as int) <= 0) {
        atomicState.calPct = null
        atomicState.calHours = null
        atomicState.calN = 0
    }
    refreshEstJobPct()
    log.info "NavimowHA: Dropped last sample slot, n=" + (atomicState.calN ?: 0) + " (no older snapshot)"
}

def applyCalPct(pct) {
    if (!haIsDocked()) {
        logDbg "Cal % ignored, not docked"
        return
    }
    saveCalUndo()
    def hoursNow = (atomicState.mowHoursWeek ?: 0) as double
    if ((pct as double) == 0.0d) {
        markNewJob()
        snapEstToPct(0, hoursNow)
        log.info "NavimowHA: Typed 0%, new map"
        clearCalSettings()
        return
    }
    if (atomicState.calPct != null && !sameCalJob(pct) && atomicState.calAutoZero != true) {
        snapEstToPct(pct, hoursNow)
        atomicState.calPct = pct
        atomicState.calHours = hoursNow
        atomicState.calN = 1
        atomicState.calAutoZero = false
        atomicState.jobMowH = 0
        atomicState.jobFromZero = false
        log.info "NavimowHA: Cal " + pct + "% new map, hours zeroed, no job store"
        refreshEstJobPct()
        clearCalSettings()
        return
    }
    def prevPct = atomicState.calPct
    def prevH = atomicState.calHours
    def n = (atomicState.calN ?: 0) as int
    def auto0 = (atomicState.calAutoZero == true && n == 0)
    if (auto0 && prevH != null && (pct as double) > 0.0d) {
        learnRateFromPair(0.0, pct as double, prevH as double, hoursNow)
    } else if (n >= 1 && prevPct != null && prevH != null && (pct as double) > (prevPct as double)) {
        learnRateFromPair(prevPct as double, pct as double, prevH as double, hoursNow)
    } else if (n == 0 && !auto0 && pct >= 95 && atomicState.lastDoneHours != null) {
        learnRateFromPair(0.0, 100.0, atomicState.lastDoneHours as double, hoursNow)
    }
    snapEstToPct(pct, hoursNow)
    atomicState.calPct = pct
    atomicState.calHours = hoursNow
    atomicState.calN = n + 1
    atomicState.calAutoZero = false
    logDbg "Cal entry " + atomicState.calN + " at " + pct + "%"
    if (pct >= 100) {
        atomicState.lastDoneHours = hoursNow
        storeJobMowHours()
        markNewJob()
        logDbg "Cal 100% closed the map, first entry next"
    } else {
        refreshEstJobPct()
    }
    clearCalSettings()
}

def applyCoverage() {
    if (atomicState.skipCalApply == true) {
        atomicState.skipCalApply = false
        clearCalSettings()
        return
    }
    def pct
    try { if (calPctNow != null) pct = calPctNow as double } catch (ignored) {}
    if (pct != null && pct >= 0 && pct <= 100) applyCalPct(pct)
}

def blendBatt(old, neu) {
    if (old == null) return rnd(neu, 0)
    return rnd(0.6 * (old as double) + 0.4 * (neu as double), 0)
}

def noteBattCycle(was, st, batt) {
    if (serviceHold == true) return
    if (batt == null || was == null || was == st) return
    def b
    try { b = batt as double } catch (ignored) { return }
    def who = "${atomicState.dockWho ?: ''}"
    def leaveB = b
    try {
        if (atomicState.lastDockedBatt != null) leaveB = atomicState.lastDockedBatt as double
    } catch (ignored) {}
    def navimowLeave = (was == "docked" && st == "mowing" && who == "charge")
    if (navimowLeave && leaveB >= 70 && leaveB <= 100) {
        atomicState.leaveBatt = rnd(leaveB, 0)
        def avgL = blendBatt(atomicState.avgLeaveBatt, leaveB)
        if (avgL < 70) avgL = 70
        if (avgL > 100) avgL = 100
        atomicState.avgLeaveBatt = avgL
        def t0 = atomicState.chargeTick
        def b0 = atomicState.chargeBatt
        if (t0 != null && b0 != null) {
            def h = (now() - (t0 as long)) / 3600000.0
            def dlt = leaveB - (b0 as double)
            if (h > 4) h = 4
            if (h >= 0.2 && dlt >= 20) {
                def rate = dlt / h
                if (rate >= 10 && rate <= 80) {
                    def old = atomicState.chargePctPerHour ?: rate
                    atomicState.chargePctPerHour = rnd(0.6 * (old as double) + 0.4 * rate, 1)
                }
            }
        }
        atomicState.mowTick = now()
        atomicState.mowBatt = leaveB
        log.info "NavimowHA: Leave dock at ${atomicState.leaveBatt}% (last docked poll, Navimow)"
    } else if (was == "docked" && st == "mowing") {
        atomicState.mowTick = null
        atomicState.mowBatt = null
    }
    def est = 0
    try { est = rawEstJobPct() as double } catch (ignored) { est = 0 }
    def navimowReturnOk = who != "gate" && who != "done" &&
        !estInDoneBand(est) &&
        !(atomicState.raining == true) &&
        !(atomicState.rainLock == true)
    def retB = b
    try {
        if (atomicState.lastMowingBatt != null) retB = atomicState.lastMowingBatt as double
    } catch (ignored) {}
    def atReturnStart = (was == "mowing" && st == "returning")
    def atDockFallback = (was in ["mowing", "returning"] && st == "docked" &&
        atomicState.returnSnapPending != true)
    if ((atReturnStart || atDockFallback) && navimowReturnOk &&
        retB >= (cfg("returnMin") as double) && retB <= (cfg("returnMax") as double)) {
        atomicState.returnBatt = rnd(retB, 0)
        atomicState.avgReturnBatt = blendBatt(atomicState.avgReturnBatt, retB)
        if (atReturnStart) atomicState.returnSnapPending = true
        def t0 = atomicState.mowTick
        def b0 = atomicState.mowBatt
        if (t0 != null && b0 != null) {
            def h = (now() - (t0 as long)) / 3600000.0
            def dlt = (b0 as double) - retB
            if (h > 4) h = 4
            if (h >= 0.2 && dlt >= 20) {
                def rate = dlt / h
                if (rate >= 10 && rate <= 80) {
                    def old = atomicState.mowPctPerHour ?: rate
                    atomicState.mowPctPerHour = rnd(0.6 * (old as double) + 0.4 * rate, 1)
                }
            }
        }
        atomicState.chargeTick = now()
        atomicState.chargeBatt = retB
        log.info "NavimowHA: Return pack at ${atomicState.returnBatt}% last mowing poll " + (atReturnStart ? "(returning)" : "(dock fallback)")
    }
    if (st == "docked") atomicState.returnSnapPending = false
}

def snapEstToPct(pct, hoursNow) {
    def hpp = hoursPerPass() as double
    if (hpp <= 0) hpp = 10.27
    def used = ((pct as double) / 100.0) * hpp
    atomicState.jobHoursBase = ((hoursNow ?: 0) as double) - used
    refreshEstJobPct()
    logDbg "Snap est job % to " + pct
}

def markNewJob() {
    def h = (atomicState.mowHoursWeek ?: 0) as double
    atomicState.jobHoursBase = h
    atomicState.calN = 0
    atomicState.calPct = 0
    atomicState.calHours = h
    atomicState.calAutoZero = true
    atomicState.jobMowH = 0
    atomicState.jobFromZero = true
    atomicState.dockWho = "gate"
    atomicState.chargeHold = false
    def done = atomicState.passesDone
    def cap = atomicState.pacedCap
    def met = false
    try { met = done != null && cap != null && ((done as double) + 0.02d) >= (cap as double) } catch (ignored) { met = false }
    atomicState.nextJobAfterMs = met ? nextFirstLightMs() : null
    def hist = atomicState.slicePctHist ?: ""
    if (hist) {
        def tot = sliceHistSum(hist)
        if ((tot.sum as double) < 100.0d) {
            def rest = rnd(100.0d - (tot.sum as double), 1)
            if (rest > 0) hist = hist + ",${rest}:close"
        }
        atomicState.lastMapSliceHist = hist
    }
    atomicState.slicePctHist = ""
    atomicState.lastSlicePct = null
    atomicState.avgSlicePct = null
    refreshEstJobPct()
    log.info "NavimowHA: New job, dock-who gate"
}

def noteMapFinished(hoursNow) {
    def h = hoursNow
    if (h == null) h = (atomicState.mowHoursWeek ?: 0) as double
    def calN = (atomicState.calN ?: 0) as int
    if (calN >= 1 && atomicState.calPct != null && atomicState.calHours != null) {
        learnRateFromPair(atomicState.calPct as double, 100.0, atomicState.calHours as double, h)
    } else if (atomicState.lastDoneHours != null) {
        learnRateFromPair(0.0, 100.0, atomicState.lastDoneHours as double, h)
    }
    atomicState.lastDoneHours = h
    storeJobMowHours()
    markNewJob()
}

def dropPartialLastJob() {
    if (atomicState.droppedPartialJob186 == true) return
    def list = jobMowList()
    if (list.size() >= 2) {
        def last = list[list.size() - 1] as double
        def prev = list[list.size() - 2] as double
        if (prev >= 8 && last < (prev * 0.75d)) {
            list.remove(list.size() - 1)
            atomicState.jobMowHist = list.join(",")
            def errs = jobErrList()
            if (errs.size() >= list.size() + 1) {
                errs.remove(errs.size() - 1)
                atomicState.jobErrHist = errs.collect { it == null ? "" : "${it}" }.join(",")
            }
            log.info "NavimowHA: Dropped partial job " + rnd(last, 2) + "h keep " + rnd(prev, 2) + "h"
        }
    }
    atomicState.droppedPartialJob186 = true
}

def storeJobMowHours() {
    def h = 0
    try { h = (atomicState.jobMowH ?: 0) as double } catch (ignored) { h = 0 }
    def err = 0
    try { err = (rawEstJobPct() as double) - 100.0d } catch (ignored) { err = 0 }
    atomicState.lastDoneErr = rnd(err, 1)
    if (atomicState.jobFromZero != true) {
        log.info "NavimowHA: Skip job hours " + rnd(h, 2) + "h (map did not start at 0)"
        return
    }
    def list = jobMowList()
    list << rnd(h, 2)
    while (list.size() > 36) list.remove(0)
    atomicState.jobMowHist = list.join(",")
    def errs = jobErrList()
    while (errs.size() < list.size() - 1) errs << null
    errs << rnd(err, 1)
    while (errs.size() > list.size()) errs.remove(0)
    while (errs.size() > 36) errs.remove(0)
    atomicState.jobErrHist = errs.collect { it == null ? "" : "${it}" }.join(",")
    log.info "NavimowHA: Job mow " + rnd(h, 2) + "h err " + rnd(err, 1) + " n=" + list.size()
}

def jobErrList() {
    def out = []
    def raw = "${atomicState.jobErrHist ?: ''}"
    if (!raw && !atomicState.jobMowHist) return out
    raw.split(",", -1).each { p ->
        def s = "${p}".trim()
        if (!s) out << null
        else {
            try { out << (s as double) } catch (ignored) { out << null }
        }
    }
    return out
}

def jobMowList() {
    def out = []
    def raw = "${atomicState.jobMowHist ?: ''}"
    if (!raw) return out
    raw.split(",").each { p ->
        try {
            if ("${p}".trim()) out << ("${p}".trim() as double)
        } catch (ignored) {}
    }
    return out
}

def jobMowStatusText() {
    def list = jobMowList()
    if (!list) return "Finished maps: none"
    def n = list.size()
    def last = list[n - 1]
    def errS = ""
    def errs = jobErrList()
    if (n - 1 < errs.size() && errs[n - 1] != null) {
        def e = errs[n - 1] as double
        def sign = e >= 0 ? "+" : ""
        errS = "  err ${sign}${d2(e)}%"
    }
    return "Finished maps: ${n}   last ${d2(last)} h${errS}"
}

def jobMowHistText() {
    def list = jobMowList()
    if (!list) return "none"
    def n = list.size()
    def sum = 0
    def mn = list[0]
    def mx = list[0]
    list.each { v ->
        sum += v
        if (v < mn) mn = v
        if (v > mx) mx = v
    }
    def mean = sum / n
    def last = list[n - 1]
    def errs = jobErrList()
    def last6n = Math.min(6, n)
    def last6 = []
    ((n - last6n)..(n - 1)).each { i ->
        def cell = d2(list[i])
        if (i < errs.size() && errs[i] != null) {
            def e = errs[i] as double
            def sign = e >= 0 ? "+" : ""
            cell = cell + "/" + sign + d2(e)
        }
        last6 << cell
    }
    def errS = ""
    if (n - 1 < errs.size() && errs[n - 1] != null) {
        def lastE = errs[n - 1] as double
        def sign = lastE >= 0 ? "+" : ""
        errS = " · last err ${sign}${d2(lastE)}"
    }
    return "n=${n} last ${d2(last)} mean ${d2(mean)} ${d2(mn)}–${d2(mx)} · last6 ${last6.join(" ")}${errS}"
}

def learnRateFromPair(fromPct, toPct, fromH, toH) {
    def dH = (toH as double) - (fromH as double)
    def dP = (toPct as double) - (fromPct as double)
    if (dH <= 0 || dP <= 0) return
    def acres = (dP / 100.0) * ((lawnAcres ?: 1.00) as double)
    def obs = acres / dH
    if (obs < 0.08 || obs > 0.22) return
    def old = cutRate()
    atomicState.learnedRate = rnd((0.7 * old) + (0.3 * obs), 3)
    logDbg "Rate from " + fromPct + "% to " + toPct + "% over " + rnd(dH, 2) + "h -> " + atomicState.learnedRate
    noteBladeWearSample("cal", rnd(obs, 3))
}

def estJobPct() {
    def mapped = (lawnAcres ?: 1.00) as double
    def rate = cutRate()
    def hpp = (rate > 0) ? (mapped / rate) : 10.27
    def base = (atomicState.jobHoursBase ?: 0) as double
    def hrs = ((atomicState.mowHoursWeek ?: 0) as double) - base
    if (hrs < 0) hrs = 0
    atomicState.jobRolled = false
    def pct = (hpp > 0) ? (hrs / hpp * 100.0) : 0
    if (pct > 200) pct = 200
    atomicState.jobOverH = 0
    return rnd(pct, 1)
}

def refreshEstJobPct() {
    atomicState.estJobPct = estJobPct()
}

def weekdayMon0() {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    def d = cal.get(Calendar.DAY_OF_WEEK)
    return (d + 5) % 7
}

def jobOpen() {
    if (atomicState.calAutoZero == true && ((atomicState.calN ?: 0) as int) == 0) return false
    return true
}

def debtMaps() {
    def h = 0
    try { h = (atomicState.debtCarryH ?: 0) as double } catch (ignored) { h = 0 }
    if (h < 0) h = 0
    def hpp = hoursPerPass() as double
    if (hpp <= 0) return 0
    return h / hpp
}

def paceDue(target) {
    def t = target as double
    def day = weekdayMon0()
    def due = ((day + 1) / 7.0d) * t
    if (day == 6) due = t
    if (atomicState.mowAhead == true) {
        def dryLeft = dryDaysLeft()
        if (dryLeft != null && (dryLeft as double) >= 1) {
            def remain = t - due
            if (remain < 0) remain = 0
            due = due + (remain / (dryLeft as double))
        } else due = t
    }
    if (due > t) due = t
    if (due < 0) due = 0
    return due
}

def pacedCap(target, done) {
    def t = target as double
    def d = (done ?: 0) as double
    def due = paceDue(t)
    if (d + 0.02d >= due) atomicState.debtCarryH = 0
    def cap
    if (weekdayMon0() == 6) {
        def remain = t - d
        if (remain < 0) remain = 0
        cap = d + remain
        if (cap < 0.4) cap = 0.4
        if (cap > t) cap = t
    } else if (todayPrecious()) {
        cap = d + 1.0
        if (cap > t) cap = t
        if (cap < 0.4) cap = 0.4
    } else if (atomicState.mowAhead) {
        def dryLeft = dryDaysLeft()
        atomicState.dryDaysLeft = dryLeft
        if (dryLeft < 1) cap = t
        else {
            def remain = t - d
            if (remain < 0) remain = 0
            cap = d + (remain / dryLeft)
            if (cap < 0.4) cap = 0.4
            if (cap > t) cap = t
        }
    } else {
        def day = weekdayMon0()
        cap = ((day + 1) / 7.0) * t
        if (cap < 0.4) cap = 0.4
        if (cap > t) cap = t
    }
    cap = (cap as double) + debtMaps()
    return rnd(cap, 2)
}

def meanTempF() {
    def live = atomicState.tempF
    def n = (atomicState.tempN3 ?: 0) as int
    def s = atomicState.tempSum3
    if (n <= 0 || s == null) return live
    def mean
    try { mean = (s as double) / n } catch (ignored) { return live }
    if (mean < -40 || mean > 130) return live
    return rnd(mean, 1)
}

def noteTemp3(tf, nowt) {
    if (tf == null) return
    def lastT = (atomicState.lastTempSampleMs ?: 0) as long
    def n3 = (atomicState.tempN3 ?: 0) as int
    if (lastT && (nowt - lastT) < 15L * 60000L && n3 > 0) return
    def cut = nowt - 72L * 3600000L
    def raw = "${atomicState.tempHist ?: ''}"
    def parts = raw ? raw.tokenize("|") : []
    def keep = []
    def sum = 0.0
    def n = 0
    parts.each { p ->
        def bits = "${p}".tokenize(":")
        if (bits.size() < 2) return
        def ms
        def v
        try { ms = bits[0] as long; v = bits[1] as double } catch (ignored) { return }
        if (ms >= cut && v > -40 && v < 130) {
            keep.add("${ms}:${v}")
            sum += v
            n++
        }
    }
    keep.add("${nowt}:${tf}")
    sum += (tf as double)
    n++
    if (keep.size() > 300) keep = keep[(keep.size() - 300)..(keep.size() - 1)]
    atomicState.tempHist = keep.join("|")
    atomicState.tempSum3 = rnd(sum, 1)
    atomicState.tempN3 = n
    atomicState.lastTempSampleMs = nowt
}

def weekDateList(offWeeks) {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    def dow = cal.get(Calendar.DAY_OF_WEEK)
    def back = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY)
    cal.add(Calendar.DATE, -back)
    if (offWeeks) cal.add(Calendar.DATE, (offWeeks as int) * 7)
    def out = []
    7.times {
        try { out << cal.getTime().format("yyyy-MM-dd", location.timeZone) }
        catch (ignored) { out << cal.getTime().format("yyyy-MM-dd") }
        cal.add(Calendar.DATE, 1)
    }
    return out
}

def parseRecentDaily() {
    if (!wxDev) return []
    def raw = attr(wxDev, ["recentDaily"])
    if (!raw) return []
    def rows = []
    "${raw}".tokenize("|").each { p ->
        def bits = "${p}".tokenize(":")
        if (bits.size() < 3) return
        def d = "${bits[0]}"
        def mx = null
        def mn = null
        def pr = null
        try { if (bits[1]) mx = bits[1] as double } catch (ignored) {}
        try { if (bits[2]) mn = bits[2] as double } catch (ignored) {}
        try { if (bits.size() > 3 && bits[3]) pr = bits[3] as double } catch (ignored) {}
        rows << [d: d, mx: mx, mn: mn, pr: pr]
    }
    return rows
}

def mondayFromYmd(ymd) {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    cal.setTime(Date.parse("yyyy-MM-dd", "${ymd}"))
    def dow = cal.get(Calendar.DAY_OF_WEEK)
    def back = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY)
    cal.add(Calendar.DATE, -back)
    try { return cal.getTime().format("yyyy-MM-dd", location.timeZone) }
    catch (ignored) { return cal.getTime().format("yyyy-MM-dd") }
}

def seedGddFromMeteo() {
    def rows = parseRecentDaily()
    atomicState.gddSeedN = rows ? rows.size() : 0
    if (!rows) return
    def anchor = rows[rows.size() - 1].d
    def mon = mondayFromYmd(anchor)
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    cal.setTime(Date.parse("yyyy-MM-dd", mon))
    cal.add(Calendar.DATE, -7)
    def prevMon
    try { prevMon = cal.getTime().format("yyyy-MM-dd", location.timeZone) }
    catch (ignored) { prevMon = cal.getTime().format("yyyy-MM-dd") }
    def have = [:]
    "${atomicState.gddDays ?: ''}".tokenize("|").each { p ->
        def bits = "${p}".tokenize(":")
        if (bits.size() >= 2) have[bits[0]] = bits[1]
    }
    def sum = 0.0
    def n = 0
    def prevIn = 0.0
    rows.each { r ->
        def d = "${r.d}"
        if (r.pr != null && d >= prevMon && d < mon) prevIn += (r.pr as double)
        if (d < mon || d > anchor) return
        if (d == anchor) return
        if (r.mx == null || r.mn == null) return
        def gdd = ((r.mx as double) + (r.mn as double)) / 2.0 - 50.0
        if (gdd < 0) gdd = 0
        have[d] = "${rnd(gdd, 1)}"
    }
    def keep = []
    have.each { k, v ->
        if ("${k}" < mon || "${k}" > anchor) return
        keep.add("${k}:${v}")
        try { sum += (v as double); n++ } catch (ignored) {}
    }
    keep.sort()
    atomicState.gddDays = keep.join("|")
    if (n > 0) atomicState.gddWeek = rnd(sum, 1)
    atomicState.gddMon = mon
    if (prevIn > 0) atomicState.meteoPrevWeekIn = rnd(prevIn, 2)
}

def noteDailyGdd() {
    if (!wxDev && atomicState.tempF == null) return
    try { seedGddFromMeteo() } catch (ignored) {}
    def day
    try { day = new Date().format("yyyy-MM-dd", location.timeZone) } catch (ignored) { day = "${new Date()}" }
    if (atomicState.gddDay != day) {
        atomicState.gddTodayMax = null
        atomicState.gddTodayMin = null
        atomicState.gddDay = day
    }
    def tmax = null
    def tmin = null
    if (wxDev) {
        tmax = toF(attr(wxDev, ["temperatureMax", "tempMax", "forecastHigh", "high"]))
        tmin = toF(attr(wxDev, ["temperatureMin", "tempMin", "forecastLow", "low"]))
    }
    def live = atomicState.tempF
    def mx = atomicState.gddTodayMax
    def mn = atomicState.gddTodayMin
    if (tmax != null) mx = (mx == null) ? (tmax as double) : Math.max(mx as double, tmax as double)
    if (tmin != null) mn = (mn == null) ? (tmin as double) : Math.min(mn as double, tmin as double)
    if (live != null) {
        def lv = live as double
        mx = (mx == null) ? lv : dmax(mx, lv)
        mn = (mn == null) ? lv : dmin(mn, lv)
    }
    atomicState.gddTodayMax = mx
    atomicState.gddTodayMin = mn
    if (mx == null || mn == null) return
    def gdd = ((mx as double) + (mn as double)) / 2.0 - 50.0
    if (gdd < 0) gdd = 0
    gdd = rnd(gdd, 1)
    def raw = "${atomicState.gddDays ?: ''}"
    def parts = raw ? raw.tokenize("|") : []
    def keep = []
    parts.each { p ->
        def bits = "${p}".tokenize(":")
        if (bits.size() < 2) return
        if (bits[0] == day) return
        keep.add(p)
    }
    keep.add("${day}:${gdd}")
    def mon = mondayFromYmd(day)
    keep = keep.findAll { p ->
        def bits = "${p}".tokenize(":")
        bits && "${bits[0]}" >= mon && "${bits[0]}" <= day
    }
    atomicState.gddDays = keep.join("|")
    def sum = 0.0
    keep.each { p ->
        def bits = "${p}".tokenize(":")
        if (bits.size() < 2) return
        try { sum += (bits[1] as double) } catch (ignored) {}
    }
    atomicState.gddWeek = rnd(sum, 1)
    atomicState.gddToday = gdd
}

def gddGrowthMult(weekGdd) {
    if (weekGdd == null) return 1.0
    def g = weekGdd as double
    if (g < 50) return 0.30
    if (g < 90) return 0.60
    if (g < 120) return 0.90
    if (g <= 170) return 1.15
    if (g <= 210) return 0.85
    return 0.55
}

def tempGrowthMult(tf) {
    if (tf == null) return 1.0
    def t = tf as double
    if (t < 40) return 0.30
    if (t < 50) return 0.60
    if (t < 60) return 0.90
    if (t <= 75) return 1.15
    if (t <= 85) return 0.85
    return 0.55
}

def daylightPassCap() {
    def cut = hoursPerPass() as double
    def wall = cut * 1.7
    if (wall < 8) wall = 8
    def cap = rnd(77.0 / wall, 0) as int
    if (cap < 1) cap = 1
    return cap
}

def lastWeekInches() {
    def inn = atomicState.rainInchesPrevWeek
    if (inn != null) {
        try {
            def v = inn as double
            if (v > 0) return v
        } catch (ignored) {}
    }
    def met = atomicState.meteoPrevWeekIn
    if (met != null) {
        try {
            def mv = met as double
            if (mv > 0) return mv
        } catch (ignored) {}
    }
    return 0.0d
}

def targetPasses() {
    def m = hubMonth()
    def rainPrev = (atomicState.rainHoursPrevWeek ?: 0) as double
    def rainH = rainPrev
    def inches = lastWeekInches()
    atomicState.rainHoursGrowth = rnd(rainH, 1)
    atomicState.rainInchesGrowth = inches
    def phys = daylightPassCap()

    def season
    def seasonWhy
    if (m in [12, 1, 2]) { season = 1.3; seasonWhy = "winter" }
    else if (m in [7, 8]) { season = 2.0; seasonWhy = "summer slump" }
    else if (m == 11) { season = 2.0; seasonWhy = "late fall" }
    else { season = 2.7; seasonWhy = "spring/fall flush" }

    def tmean = meanTempF()
    def weekGdd = atomicState.gddWeek
    def gddN = 0
    def rawG = "${atomicState.gddDays ?: ''}"
    if (rawG) gddN = rawG.tokenize("|").size()
    def paced = null
    if (gddN >= 3 && weekGdd != null && (weekGdd as double) > 0) {
        paced = ((weekGdd as double) / gddN) * 7.0
    } else if (gddN < 3) {
        def prev = atomicState.gddPrevWeek
        if (prev != null) {
            try {
                def pv = prev as double
                if (pv > 0) paced = pv
            } catch (ignored) {}
        }
    }
    atomicState.gddPace = (paced != null) ? rnd(paced, 1) : null
    def tmult
    def heatWhy
    if (paced != null) {
        tmult = gddGrowthMult(paced)
        if (gddN >= 3) heatWhy = "GDD pace " + rnd(paced, 1) + " (" + weekGdd + "/" + gddN + "d) x" + tmult
        else heatWhy = "GDD last week " + rnd(paced, 1) + " x" + tmult
    } else {
        tmult = tempGrowthMult(tmean)
        heatWhy = "3-day T=" + tmean + "x" + tmult
    }
    def rmult
    def rainWhy
    if (inches >= 1.50) { rmult = 1.35; rainWhy = "very wet last week " + inches + "in" }
    else if (inches >= 0.75) { rmult = 1.20; rainWhy = "wet last week " + inches + "in" }
    else if (inches >= 0.20) { rmult = 1.00; rainWhy = "some rain last week " + inches + "in" }
    else { rmult = 0.85; rainWhy = "dry last week " + inches + "in" }

    def score = season * tmult * rmult
    def wetNudge = 0
    def lf = (atomicState.prevLeafWetFrac ?: 0) as double
    def sf = (atomicState.prevSoilWetFrac ?: 0) as double
    if (leafDev && lf > 0.5) {
        wetNudge = 0.3
        score = score + 0.3
    }
    if (soilDev && sf > 0.5) {
        wetNudge = wetNudge + 0.3
        score = score + 0.3
    }
    atomicState.growthScore = rnd(score, 2)
    def cand = growthBand(score, tmean, paced, inches, rainH, m, phys)
    def carry = (atomicState.passCarry ?: 0) as double
    if (carry >= 0.5 && cand < phys) cand = cand + 1
    if (cand > phys) cand = phys
    if (cand < 1) cand = 1
    def tgt = holdGrowthTarget(cand)
    def done = 0
    try {
        def hpp = hoursPerPass() as double
        if (hpp > 0) done = ((atomicState.mowHoursWeek ?: 0) as double) / hpp
    } catch (ignored) {}
    def floorT = Math.floor((done as double) + 0.02) as int
    if (floorT < 1) floorT = 1
    if (((atomicState.growthHeld ?: 0) as int) > cand && cand >= floorT) {
        atomicState.growthHeld = cand
        tgt = cand
    }
    if (tgt < floorT) tgt = floorT
    if (tgt > phys) tgt = phys
    def why = seasonWhy + ", " + heatWhy + ", " + rainWhy + " score " + rnd(score, 2)
    if (wetNudge > 0) why = why + ", device-wet +0.3"
    if (carry > 0) why = why + ", carry " + atomicState.carryNote
    why = why + " band " + cand + " held " + (atomicState.growthHeld ?: tgt) + " floor " + floorT + " -> " + tgt + "x"
    atomicState.growthWhy = why
    return tgt
}

def growthBand(score, tmean, paced, inches, rainH, month, phys) {
    def s = score as double
    def t = (tmean != null) ? (tmean as double) : 60
    if ((month in [12, 1, 2] && s < 1.6) || t < 45) {
        return 1
    }
    if (s < 1.85) return 2
    if (s < 2.90) return 3
    def hot = (paced != null && (paced as double) >= 170)
    def inn = 0
    try { inn = (inches != null) ? (inches as double) : 0 } catch (ignored) { inn = 0 }
    def wet = (inn >= 0.75d)
    if (hot && wet && phys >= 4) return 4
    return 3
}

def holdGrowthTarget(cand) {
    def c = cand as int
    def today
    try { today = new Date().format("yyyy-MM-dd", location.timeZone) } catch (ignored) { today = "${new Date()}" }
    def held = atomicState.growthHeld
    if (held == null) {
        atomicState.growthHeld = c
        atomicState.growthPend = c
        atomicState.growthPendN = 1
        atomicState.growthDay = today
        return c
    }
    held = held as int
    if (c == held) {
        atomicState.growthPend = c
        atomicState.growthPendN = 0
        atomicState.growthDay = today
        return held
    }
    if (atomicState.growthDay != today) {
        atomicState.growthDay = today
        if ((atomicState.growthPend ?: 0) as int == c) {
            atomicState.growthPendN = ((atomicState.growthPendN ?: 0) as int) + 1
        } else {
            atomicState.growthPend = c
            atomicState.growthPendN = 1
        }
        if ((atomicState.growthPendN ?: 0) >= 2) {
            atomicState.growthHeld = c
            return c
        }
    }
    return held
}

def wxEvent(evt) {
    readHubitatWeather()
    decideSafe()
}

def pollMower() {
    def stamp = weekStamp()
    if (stamp != atomicState.weekStamp) {
        def lastT = (atomicState.targetPasses ?: 0) as double
        def lastD = (atomicState.passesDone ?: 0) as double
        def weekStart = mondayStartMs()
        def prevWeekStart = weekStart - 7L * 24L * 3600000L
        def first = (atomicState.firstLiveMs ?: now()) as long
        def full = first <= prevWeekStart
        atomicState.passCarry = 0
        atomicState.carryNote = "off (partial week)"
        if (full) {
            def shortfall = lastT - lastD
            if (shortfall < 0) shortfall = 0
            def add = 0.5 * shortfall
            if (add > 1) add = 1
            atomicState.passCarry = rnd(add, 2)
            atomicState.carryNote = "+${atomicState.passCarry} from last week ${rnd(lastD, 2)} / ${lastT}"
        }
        def ln = (atomicState.leafSampN ?: 0) as int
        def lw = (atomicState.leafWetN ?: 0) as int
        def sn = (atomicState.soilSampN ?: 0) as int
        def sw = (atomicState.soilWetN ?: 0) as int
        atomicState.prevLeafWetFrac = (ln > 0) ? (lw / (ln as double)) : 0
        atomicState.prevSoilWetFrac = (sn > 0) ? (sw / (sn as double)) : 0
        atomicState.leafSampN = 0
        atomicState.leafWetN = 0
        atomicState.soilSampN = 0
        atomicState.soilWetN = 0
        atomicState.rainHoursPrevWeek = atomicState.rainHoursWeek ?: 0
        atomicState.rainInchesPrevWeek = atomicState.rainInchesWeek ?: 0
        atomicState.gddPrevWeek = atomicState.gddWeek
        atomicState.gddDays = ""
        atomicState.gddWeek = 0
        atomicState.gddTodayMax = null
        atomicState.gddTodayMin = null
        atomicState.gddDay = null
        atomicState.mowHoursWeek = 0
        atomicState.dockHoursWeek = 0
        atomicState.chargeHoursWeek = 0
        atomicState.dockIdleHoursWeek = 0
        atomicState.serviceHoursWeek = 0
        atomicState.returnHoursWeek = 0
        atomicState.rainHoursWeek = 0
        atomicState.rainInchesWeek = 0
        atomicState.weatherDebtH = 0
        atomicState.growthHeld = null
        atomicState.growthPend = null
        atomicState.growthPendN = 0
        atomicState.growthDay = null
        atomicState.coverageAcres = 0
        atomicState.lastCoverageApplied = null
        def holdPct = atomicState.calPct
        atomicState.calHours = 0
        if (holdPct != null) {
            snapEstToPct(holdPct, 0)
            logDbg "New week, job still open at " + holdPct + "%"
        } else {
            atomicState.jobHoursBase = 0
        }
        logDbg "New week, prev rain h " + atomicState.rainHoursPrevWeek + " carry " + atomicState.carryNote
    }
    atomicState.weekStamp = stamp
    readHubitatWeather()
    fetchHaState(mowerEntity, "mower")
}

def attr(dev, names) {
    if (!dev) return null
    for (n in names) {
        try {
            def v = dev.currentValue(n)
            if (v != null && v != "null") return v
        } catch (ignored) {}
    }
    return null
}

def readHubitatWeather() {
    if (!wxDev) {
        atomicState.lastError = "No Hubitat weather device selected"
        return
    }
    def t = attr(wxDev, ["temperature"])
    def rh = attr(wxDev, ["humidity"])
    def dp = attr(wxDev, ["dewpoint", "dewPoint", "dew_point"])
    def cond = attr(wxDev, ["weather", "condition", "forecast", "weatherCondition"])
    def wind = attr(wxDev, ["windSpeed", "wind", "windspeed"])
    def clouds = attr(wxDev, ["cloudiness", "cloudCover", "cloud"])
    def solar = attr(wxDev, ["solarRadiation", "shortwaveRadiation", "irradiance", "illuminance"])
    def rate = attr(wxDev, ["precipRate", "rainRate", "precipitation"])
    def fc = readForecastChance(wxDev)
    def fcPct = fc.pct
    def fcText = fc.text ?: ""
    atomicState.forecastRainPct = fcPct
    atomicState.forecastText = fcText
    def ahead = false
    def needPct = cfg("forecastRainPct") as int
    def late = lateWeekLost(wxDev)
    atomicState.forecastLateWeekPct = late.maxPop
    if (late.lost) ahead = true
    def afterLight = false
    try {
        def ss = getSunriseAndSunset()
        def pad = (afterSunsetMin() as int) * 60000L
        afterLight = now() >= (ss.sunset.time + pad)
    } catch (ignored) {}
    if (!afterLight && fcPct != null && (fcPct as double) >= needPct) ahead = true
    if (afterLight && late.maxPop != null && (late.maxPop as double) >= needPct) ahead = true
    atomicState.mowAhead = ahead

    atomicState.tempF = toF(t)
    atomicState.humidity = rh
    atomicState.dewF = (dp != null) ? toF(dp) : dewFromRh(atomicState.tempF, rh)
    atomicState.weatherState = cond ? "${cond}" : ""
    atomicState.wind = toNum(wind)
    atomicState.clouds = toNum(clouds)
    atomicState.solarWm2 = solarWm2()

    def spread = null
    if (atomicState.tempF != null && atomicState.dewF != null) {
        spread = rnd((atomicState.tempF as double) - (atomicState.dewF as double), 1)
    }
    atomicState.spreadF = spread
    noteSpread(spread)
    atomicState.grassOffF = grassOffsetF()
    def gSpread = grassSpreadF()
    atomicState.grassSpreadF = gSpread

    def wetTh = cfg("dewWetF") as double
    def dryTh = cfg("dewDryF") as double
    def dewOn = atomicState.dewOn ? true : false
    if (gSpread != null) {
        if (gSpread <= wetTh) dewOn = true
        else if (gSpread >= dryTh) dewOn = false
    } else if (spread != null) {
        if (spread <= wetTh) dewOn = true
        else if (spread >= dryTh) dewOn = false
    }
    def morningHold = false
    try {
        def ss = getSunriseAndSunset()
        morningHold = now() < (ss.sunrise.time + (dewWaitMin() * 60000L))
    } catch (ignored) {}
    if (morningHold && gSpread != null && (gSpread as double) < dryTh) dewOn = true
    atomicState.dewOn = dewOn

    if (rainGaugeDev) {
        atomicState.gaugeRate = firstNum(rainGaugeDev, ["rainRate", "precipRate", "rate"])
    }
    def gaugeRate = atomicState.gaugeRate
    def wcode = toNum(attr(wxDev, ["weatherCode"]))
    atomicState.precipToday = toNum(attr(wxDev, ["precipToday"]))
    atomicState.precipLast24 = toNum(attr(wxDev, ["precipLast24"]))
    atomicState.lastStormInches = toNum(attr(wxDev, ["lastStormInches"]))
    atomicState.et0Today = toNum(attr(wxDev, ["et0Today"]))
    atomicState.et0Next12 = toNum(attr(wxDev, ["et0Next12"]))
    atomicState.et0Last12 = toNum(attr(wxDev, ["et0Last12"]))
    atomicState.et0SinceLastPrecip = toNum(attr(wxDev, ["et0SinceLastPrecip"]))
    def lastPrecipMs = parseWxStamp(attr(wxDev, ["lastPrecipAt"]))
    def text = "${cond ?: ''} ${rate ?: ''}".toLowerCase()
    def codeWet = (wcode != null && wetWeatherCode(wcode as double))
    def recentHour = (lastPrecipMs != null && (now() - lastPrecipMs) <= 90L * 60000L)
    def raining = !!(
        (toNum(rate) != null && (toNum(rate) as double) > 0) ||
        (gaugeRate != null && (gaugeRate as double) > 0) ||
        text.find(/rain|pour|storm|shower|drizzle|thunder/) ||
        codeWet ||
        recentHour
    )
    def was = atomicState.raining ? true : false
    atomicState.raining = raining
    if (raining && !was) {
        def prev = (atomicState.lastRain ?: 0) as long
        if (prev && (now() - prev) <= 2L * 3600000L) {
            logDbg "Storm continues"
        } else {
            atomicState.stormStart = now()
            logDbg "Storm start"
        }
        atomicState.lastRain = now()
    }
    if (lastPrecipMs != null) {
        def prevR = (atomicState.lastRain ?: 0) as long
        if (lastPrecipMs > prevR) atomicState.lastRain = lastPrecipMs
    }
    if (!raining && was) {
        atomicState.lastRain = now()
        def start = (atomicState.stormStart ?: now()) as long
        atomicState.stormHours = dmax(((now() - start) / 3600000.0) as double, 0.1d)
        applyStormInches()
        def addIn = 0
        try { addIn = atomicState.stormInches as double } catch (ignored) {}
        if (addIn > 0) atomicState.rainInchesWeek = rnd(((atomicState.rainInchesWeek ?: 0) as double) + addIn, 2)
        logDbg "Storm end"
    } else {
        applyStormInches()
    }
    def nowt = now()
    def lastS = (atomicState.lastRainSample ?: nowt) as long
    def dtH = (nowt - lastS) / 3600000.0
    atomicState.lastRainSample = nowt
    if (raining && dtH > 0 && dtH < 0.26) {
        atomicState.rainHoursWeek = ((atomicState.rainHoursWeek ?: 0) as double) + dtH
    }
    if (atomicState.tempF != null) {
        try { noteTemp3(atomicState.tempF as double, nowt) } catch (ignored) {}
    }
    try { noteDailyGdd() } catch (ignored) {}
    if (!raining) {
        def lr = (atomicState.lastRain ?: 0) as long
        def last24 = 0
        try { last24 = (atomicState.precipLast24 ?: 0) as double } catch (ignored) { last24 = 0 }
        if (lr && (now() - lr) > 24L * 3600000L && last24 < 0.02 && !rainLocked()) {
            atomicState.stormHours = 0
            atomicState.stormInches = 0
            atomicState.lastRain = 0
        }
    }
    readOptionalSensors()
}

def sensorLive(dev, lastMs) {
    if (!dev) return false
    def batt = firstNum(dev, ["battery"])
    if (batt != null && (batt as double) <= 5) return false
    if (!lastMs) return false
    def mins = 24
    try { mins = (cfg("staleMin") as int) * 2 } catch (ignored) {}
    return (now() - (lastMs as long)) <= (mins * 60000L)
}

def readOptionalSensors() {
    def leaf = null
    if (leafDev) {
        leaf = firstNum(leafDev, ["leafWetness", "leaf_wetness", "wetness", "humidity"])
        if (leaf != null) atomicState.lastLeafMs = now()
    }
    if (leafDev && sensorLive(leafDev, atomicState.lastLeafMs)) {
        if (leaf != null) {
            atomicState.leafPct = rnd(leaf as double, 0)
            atomicState.leafSampN = ((atomicState.leafSampN ?: 0) as int) + 1
            if ((atomicState.leafPct as double) >= (cfg("leafWetPct") as int)) {
                atomicState.leafWetN = ((atomicState.leafWetN ?: 0) as int) + 1
            }
        }
        atomicState.leafSrc = "sensor"
    } else if (atomicState.raining || rainLocked()) {
        atomicState.leafSrc = "estimated from rain"
        atomicState.leafPct = 80
    } else {
        atomicState.leafSrc = "estimated from dew"
        if (atomicState.dewOn) atomicState.leafPct = 80
        else if (atomicState.grassSpreadF != null && (atomicState.grassSpreadF as double) <= (cfg("dewDryF") as double)) atomicState.leafPct = 40
        else atomicState.leafPct = 10
    }

    def inches = null
    def gRate = null
    if (rainGaugeDev) {
        inches = firstNum(rainGaugeDev, ["rain", "precipitation", "rainDaily", "precipToday", "rainThisEvent"])
        gRate = firstNum(rainGaugeDev, ["rainRate", "precipRate", "rate"])
    }
    atomicState.gaugeRate = gRate
    if (inches != null) {
        atomicState.gaugeInches = rnd(inches as double, 2)
        atomicState.lastGaugeMs = now()
    }
    if (gRate != null) atomicState.lastGaugeMs = now()
    if (rainGaugeDev && sensorLive(rainGaugeDev, atomicState.lastGaugeMs) && atomicState.gaugeInches != null) {
        if (atomicState.raining) atomicState.stormInches = atomicState.gaugeInches
        atomicState.rainSrc = "gauge"
    } else if (!atomicState.rainSrc) {
        atomicState.rainSrc = "estimated"
    }

    def soil = null
    if (soilDev) {
        soil = firstNum(soilDev, ["soilMoisture", "soil_moisture", "moisture", "humidity"])
        if (soil != null) atomicState.lastSoilMs = now()
    }
    if (soilDev && sensorLive(soilDev, atomicState.lastSoilMs)) {
        if (soil != null) {
            atomicState.soilPct = rnd(soil as double, 0)
            atomicState.soilSampN = ((atomicState.soilSampN ?: 0) as int) + 1
            if ((atomicState.soilPct as double) >= 45) {
                atomicState.soilWetN = ((atomicState.soilWetN ?: 0) as int) + 1
            }
        }
        atomicState.soilSrc = "sensor"
    } else {
        atomicState.soilSrc = "estimated from rain"
        def est = (atomicState.soilPct ?: 25) as double
        if (atomicState.raining) est = dmin(60, est + 6)
        else {
            def inchesNow = (atomicState.stormInches ?: 0) as double
            if (inchesNow >= 0.4 && rainLocked()) est = dmax(est, 55)
            est = dmax(15, est * 0.97)
        }
        atomicState.soilPct = rnd(est, 0)
    }
}

def mowProgressEntity() {
    def m = "${mowerEntity ?: ''}".trim()
    if (!m) return null
    def name = m.startsWith("lawn_mower.") ? m.substring("lawn_mower.".length()) : m
    if (!name) return null
    return "sensor.${name}_mow_progress"
}

def noteHaProgress(json) {
    def raw = json?.state
    atomicState.haMowProgressRaw = raw
    if (raw == null) return
    def s = "${raw}".trim().toLowerCase()
    if (s in ["unknown", "unavailable", "none", "null", ""]) return
    def n
    try { n = raw as double } catch (ignored) { return }
    if (n >= 1000) n = 100
    if (n < 0) n = 0
    if (n > 100) n = 100
    atomicState.haMowProgressLive = rnd(n, 1)
    atomicState.haMowProgress = atomicState.haMowProgressLive
    atomicState.haMowProgressMs = now()
}

def snapHaProgressBeforeDock() {
    def live = atomicState.haMowProgressLive
    if (live == null) return
    atomicState.haMowProgressSnap = live
    log.info "NavimowHA: HA path snap ${live}% before dock"
}

def haProgressLine() {
    def live = atomicState.haMowProgressLive
    def snap = atomicState.haMowProgressSnap
    def raw = atomicState.haMowProgressRaw
    if (live == null && snap == null) {
        return "HA path: — (${raw ?: "no sensor"})"
    }
    def bits = []
    if (live != null) bits << "live ${live}%"
    if (snap != null) bits << "last at dock ${snap}%"
    if (raw in ["unknown", "unavailable"]) bits << "${raw} now"
    return "HA path: " + bits.join(" · ")
}

def haBase() {
    def u = haUrl?.toString()?.trim()
    if (!u) return ""
    while (u.endsWith("/")) {
        u = u.substring(0, u.length() - 1)
    }
    return u
}

def fetchHaState(String entityId, String kind) {
    if (!haUrl || !haToken || !entityId) {
        atomicState.lastError = "Missing HA URL, token, or mower entity"
        decideSafe()
        return
    }
    def params = [
        uri: haBase() + "/api/states/${entityId}",
        headers: [
            "Authorization": "Bearer ${haToken}",
            "Content-Type": "application/json"
        ],
        timeout: 20
    ]
    asynchttpGet("gotState", params, [kind: kind])
}

def gotState(resp, data) {
    if (resp.hasError()) {
        if (data?.kind == "progress") {
            logDbg "HA progress GET ${resp.getErrorMessage()}"
            decideSafe()
            return
        }
        markHaFail("HA GET ${data?.kind}: ${resp.getErrorMessage()}")
        if (data?.kind == "mower") creditStaleHours()
        decideSafe()
        return
    }
    def json
    try {
        json = resp.json ?: new groovy.json.JsonSlurper().parseText(resp.data as String)
    } catch (e) {
        markHaFail("Parse ${data?.kind}: ${e}")
        if (data?.kind == "mower") creditStaleHours()
        decideSafe()
        return
    }
    def st0 = json?.state as String
    if (st0 in ["unavailable", "unknown", "null", ""]) {
        if (data?.kind == "progress") {
            noteHaProgress(json)
            decideSafe()
            return
        }
        markHaFail("HA ${data?.kind} state ${st0}")
        if (data?.kind == "mower") {
            atomicState.mowerState = st0
            creditStaleHours()
        }
        decideSafe()
        return
    }
    atomicState.lastError = null
    atomicState.haFails = 0
    atomicState.lastHaOk = now()
    if (data?.kind == "mower") {
        def attrStatus = json?.attributes?.status
        atomicState.haStatus = attrStatus
        def st = mapHaMowerState(st0, attrStatus)
        if (st == "returning" && atomicState.prevMowerState != "returning") {
            log.info "NavimowHA: HA returning seen state=${st0} status=${attrStatus} batt=${json?.attributes?.battery ?: json?.attributes?.battery_level}"
        }
        atomicState.mowerState = st
        def battA = json?.attributes?.battery_level ?: json?.attributes?.battery
        if (battA != null) {
            atomicState.battery = battA
            atomicState.battFromMower = true
        } else {
            atomicState.battFromMower = false
        }
        def was = atomicState.prevMowerState
        stampMowEdges(was, st)
        noteBattCycle(was, st, atomicState.battery)
        if (battA != null && st == "docked") atomicState.lastDockedBatt = battA
        if (battA != null && st == "mowing") atomicState.lastMowingBatt = battA
        creditMowerHours(st, payloadTimeMs(json))
        noteDockWho(was, st, atomicState.battery)
        refreshEstJobPct()
        atomicState.prevMowerState = st
        atomicState.lastMowTick = now()
        def keys = json?.attributes instanceof Map ? json.attributes.keySet().join(",") : ""
        atomicState.haRaw = "state=${st0} status=${attrStatus ?: 'none'} used=${st} last=${json?.last_updated ?: json?.last_changed ?: '?'} battAttr=${battA ?: 'none'} keys=${keys}"
        refreshEstJobPct()
        refreshCoverageAcres()
        def pe = mowProgressEntity()
        if (pe) fetchHaState(pe, "progress")
        else decideSafe()
    } else if (data?.kind == "progress") {
        noteHaProgress(json)
        decideSafe()
    }
}

def mapHaMowerState(st0, attrStatus) {
    def a = "${st0 ?: ''}".toLowerCase()
    def s = "${attrStatus ?: ''}".toLowerCase()
    if (a.contains("return") || s.contains("return")) return "returning"
    if (a.contains("pause") || s.contains("pause")) return "paused"
    if (a.contains("mow") || a.contains("cut") || s.contains("mow") || s.contains("cut")) return "mowing"
    if (a.contains("dock") || a.contains("charg") || a.contains("idle") || s.contains("dock") || s.contains("charg") || s.contains("idle")) return "docked"
    if (a in ["docked", "mowing", "paused", "returning", "error"]) return a
    return a ?: "unknown"
}

def decideSafe() {
    try { decide() } catch (e) {
        atomicState.lastError = "decide: ${e}"
        log.warn atomicState.lastError
    }
}

def dryFactor() {
    def factor = 1.0
    def ss = getSunriseAndSunset()
    def n = now()
    def afterRiseH = (n - ss.sunrise.time) / 3600000.0
    def beforeSetH = (ss.sunset.time - n) / 3600000.0
    if (n < ss.sunrise.time || n > ss.sunset.time) factor = factor * 1.8
    else if (afterRiseH < 2 || beforeSetH < 1.5) factor = factor * 1.4
    else factor = factor * 0.75

    def spread = atomicState.spreadF
    if (spread != null) {
        if (spread >= 10) factor = factor * 0.7
        else if (spread >= 8) factor = factor * 0.8
        else if (spread <= 3) factor = factor * 1.5
        else if (spread <= 5) factor = factor * 1.2
    }
    def wind = atomicState.wind
    if (wind != null) {
        def w = wind as double
        if (w >= 12) factor = factor * 0.75
        else if (w >= 8) factor = factor * 0.85
    }
    def clouds = atomicState.clouds
    if (clouds != null) {
        def c = clouds as double
        if (c >= 80) factor = factor * 1.35
        else if (c <= 30) factor = factor * 0.9
    }
    return rnd(dmax(factor, 0.5), 2)
}

def daylightHoursSince(ms) {
    if (!ms) return 0
    def start = ms as long
    def end = now()
    if (end <= start) return 0
    def hrs = 0.0d
    try {
        def ss = getSunriseAndSunset()
        def rise = ss.sunrise.time as long
        def set = ss.sunset.time as long
        def a = start
        if (a < rise) a = rise
        def b = end
        if (b > set) b = set
        if (b > a) hrs = (b - a) / 3600000.0d
        if (start < rise) {
            def y = sunOn(-1)
            def yset = y.sunset.time as long
            def yrise = y.sunrise.time as long
            def c = start
            if (c < yrise) c = yrise
            def d = end
            if (d > yset) d = yset
            if (d > c) hrs = hrs + ((d - c) / 3600000.0d)
        }
    } catch (ignored) {
        hrs = 0
    }
    return hrs
}

def waitHours() {
    try {
    def inches = 0
    try { inches = (atomicState.stormInches ?: 0) as double } catch (ignored) { inches = 0 }
    if (inches <= 0) return 0
    def last = (atomicState.lastRain ?: 0) as long
    if (!last && !atomicState.raining) return 0
    def film = inches
    if (film > 0.08) film = 0.08
    def k = 1.0
    def perH = et0InPerHour()
    def spread = 0
    try { spread = (atomicState.spreadF ?: 0) as double } catch (ignored) { spread = 0 }
    def daylit = false
    try {
        def ss = getSunriseAndSunset()
        daylit = now() >= ss.sunrise.time && now() <= ss.sunset.time
    } catch (ignored) {}
    if (!atomicState.raining && daylit) perH = dmax(perH, 0.015)
    def sinceH = 0
    if (last && !atomicState.raining) sinceH = dmax(0, (now() - last) / 3600000.0)
    if (!atomicState.raining) {
        if (spread >= 8) perH = dmax(perH, 0.012)
        else if (spread >= 5) perH = dmax(perH, 0.008)
    }
    def dayH = atomicState.raining ? 0 : daylightHoursSince(last)
    def nightH = dmax(0, sinceH - dayH)
    def nightPer = dmax(0.002, dmin(perH, 0.004))
    def dayPer = perH
    if (!atomicState.raining) dayPer = dmax(dayPer, 0.015)
    def used = (dayH * dayPer) + (nightH * nightPer)
    film = film - (used * k)
    if (film <= 0) {
        if (last) atomicState.filmGoneRain = last
        return 0
    }
    atomicState.filmGoneRain = null
    def dryH = waitHoursForward(film, k, perH, spread)
    return rnd(dryH, 2)
    } catch (e) {
        log.warn "NavimowHA: waitHours " + e
        return 0
    }
}

def sunWindows() {
    def out = []
    try {
        for (def i = -3; i <= 7; i++) {
            def sun = sunOn(i)
            out << [rise: sun.sunrise.time as long, set: sun.sunset.time as long]
        }
    } catch (ignored) {}
    return out
}

def inDaylight(ms, windows) {
    def t = ms as long
    for (w in windows) {
        if (t >= (w.rise as long) && t <= (w.set as long)) return true
    }
    return false
}

def waitHoursForward(filmIn, k, basePerH, spread) {
    def film = filmIn as double
    def windows = sunWindows()
    def stepH = 0.25d
    def hours = 0.0d
    def t = now()
    def rainEnd = hourlyRainClearMs()
    if (atomicState.raining == true && (rainEnd == null || (rainEnd as long) <= now())) return 0
    def wetUntil = now()
    if (atomicState.raining == true) wetUntil = rainEnd as long
    while (film > 0) {
        if ((t as long) < (wetUntil as long)) {
            hours = hours + stepH
            t = t + ((stepH * 3600000.0d) as long)
            continue
        }
        def day = inDaylight(t, windows)
        def perH = basePerH as double
        if (day) perH = dmax(perH, 0.015)
        else perH = dmax(0.002, dmin(perH, 0.004))
        if (day) {
            if (spread >= 8) perH = dmax(perH, 0.012)
            else if (spread >= 5) perH = dmax(perH, 0.008)
        }
        def take = perH * (k as double) * stepH
        if (take <= 0) take = 0.002d * stepH
        if (take >= film) {
            hours = hours + (film / take) * stepH
            return hours
        }
        film = film - take
        hours = hours + stepH
        t = t + ((stepH * 3600000.0d) as long)
    }
    return hours
}

def et0InPerHour() {
    def et0n = 0
    try { et0n = (atomicState.et0Next12 ?: 0) as double } catch (ignored) { et0n = 0 }
    def et0d = 0
    try { et0d = (atomicState.et0Today ?: 0) as double } catch (ignored) { et0d = 0 }
    def et0p = 0
    try { et0p = (atomicState.et0Last12 ?: 0) as double } catch (ignored) { et0p = 0 }
    def best = dmax(et0n, et0p)
    if (best > 0) return dmax(best / 12.0, 0.002)
    if (et0d > 0) return dmax(et0d / 24.0, 0.002)
    def vpd = 0
    try { vpd = (attr(wxDev, ["vpd"]) ?: 0) as double } catch (ignored) { vpd = 0 }
    if (vpd > 0) return dmax(0.004 * vpd, 0.002)
    def spread = 0
    try { spread = (atomicState.spreadF ?: 0) as double } catch (ignored) { spread = 0 }
    if (spread >= 8) return 0.012
    if (spread >= 5) return 0.007
    return 0.003
}

def rainLocked() {
    if (atomicState.raining) return true
    def w = waitHours()
    if (w == null) return false
    return (w as double) > 0
}

def applyStormInches() {
    def inches = 0
    def src = "none"
    def g = atomicState.gaugeInches
    if (rainGaugeDev && sensorLive(rainGaugeDev, atomicState.lastGaugeMs) && g != null) {
        try {
            inches = g as double
            src = "gauge"
        } catch (ignored) {}
        atomicState.stormInches = rnd(inches, 2)
        atomicState.rainSrc = src
        return
    }
    def cluster = atomicState.lastStormInches
    if (cluster != null) {
        try {
            def n = cluster as double
            if (n > inches) {
                inches = n
                src = "last storm"
            }
        } catch (ignored) {}
    }
    def last24 = atomicState.precipLast24
    def todayIn = atomicState.precipToday
    if (last24 != null && inches <= 0) {
        try {
            def n = last24 as double
            if (n > inches) {
                inches = n
                src = "last 24h"
            }
        } catch (ignored) {}
    }
    if (todayIn != null && inches <= 0) {
        try {
            def n = todayIn as double
            if (n > inches) {
                inches = n
                src = "today"
            }
        } catch (ignored) {}
    }
    if (inches > 0) {
        atomicState.stormInches = rnd(inches, 3)
        atomicState.rainSrc = src
    }
}

def wetWeatherCode(code) {
    def c = code as int
    return (c >= 51 && c <= 67) || (c >= 80 && c <= 82) || (c >= 95 && c <= 99)
}

def parseWxStamp(raw) {
    if (!raw || raw == "none") return null
    def s = "${raw}".trim()
    if (s.length() == 16) s = s + ":00"
    if (s.length() > 19) s = s.substring(0, 19)
    try {
        def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        try { sdf.setTimeZone(location.timeZone) } catch (ignored) {}
        def d = sdf.parse(s)
        if (d) return d.time
    } catch (ignored) {}
    try { return toDateTime(s).time } catch (ignored) {}
    return null
}

def rainRateInPerHour() {
    if (atomicState.raining != true) return 0.0d
    def r = 0
    try { r = (atomicState.gaugeRate ?: 0) as double } catch (ignored) { r = 0 }
    if (r <= 0) {
        try { r = (toNum(attr(wxDev, ["precipRate", "rainRate"])) ?: 0) as double } catch (ignored) { r = 0 }
    }
    if (r <= 0) r = 0.10d
    if (r < 0) r = 0
    return r
}

def returnTripHNow() {
    def t = 0
    try { t = (atomicState.lastReturnTripH ?: 0) as double } catch (ignored) { t = 0 }
    if (t < 0.005) {
        try { t = (atomicState.avgReturnTripH ?: 0) as double } catch (ignored) { t = 0 }
    }
    if (t < 0.005) t = 0.05d
    if (t > 1.5) t = 1.5d
    return t
}

def returnFilmDryH() {
    def rate = rainRateInPerHour()
    if (rate <= 0 && atomicState.raining != true) return 0.0d
    def inches = returnTripHNow() * rate
    if (inches > 0.08d) inches = 0.08d
    if (inches <= 0) return 0.0d
    def base = et0InPerHour()
    def windows = sunWindows()
    def stepH = 0.25d
    def film = inches
    def hours = 0.0d
    def t = now()
    while (film > 0 && hours < 48) {
        def day = inDaylight(t, windows)
        def perH = base as double
        if (day) perH = dmax(perH, 0.015)
        else perH = dmax(0.002, dmin(perH, 0.004))
        def take = perH * stepH
        if (take <= 0) take = 0.002d * stepH
        if (take >= film) {
            hours = hours + (film / take) * stepH
            return rnd(hours, 2)
        }
        film = film - take
        hours = hours + stepH
        t = t + ((stepH * 3600000.0d) as long)
    }
    return rnd(hours, 2)
}

def chargeHoursFrom(batt) {
    def tgt = 80
    try { tgt = leaveTarget() as double } catch (ignored) {}
    def chg = chargePctPerHour()
    if (chg == null || chg <= 0) return 0.0d
    def b = 0
    try { b = batt as double } catch (ignored) { return 0.0d }
    def h = (tgt - b) / chg
    if (h < 0) h = 0
    return h
}

def noteDoneSitH(batt) {
    def filmH = returnFilmDryH()
    def chgH = chargeHoursFrom(batt)
    def rem = filmH - chgH
    if (rem < 0) rem = 0
    rem = rem + 0.25d
    atomicState.doneSitH = rnd(rem, 2)
    atomicState.returnFilmH = rnd(filmH, 2)
    log.info "NavimowHA: Return film " + rnd(filmH, 2) + "h charge " + rnd(chgH, 2) + "h sit " + atomicState.doneSitH + "h"
}

def chargeSitMs() {
    def h = 0.25d
    try {
        if (atomicState.doneSitH != null) h = atomicState.doneSitH as double
    } catch (ignored) {}
    if (h < 0.25d) h = 0.25d
    return ((h * 3600000.0d) as long)
}

def inferSitFinished() {
    def st = "${atomicState.mowerState ?: ''}".toLowerCase()
    if (st.contains("mow") || st.contains("return")) {
        atomicState.fullPackSinceMs = null
        return
    }
    if (!(st.contains("dock"))) {
        atomicState.fullPackSinceMs = null
        return
    }
    if (atomicState.dockWho != "charge") {
        atomicState.fullPackSinceMs = null
        return
    }
    if (serviceHold == true || forceDock == true || enabled == false) {
        atomicState.fullPackSinceMs = null
        return
    }
    if (haStale()) {
        atomicState.fullPackSinceMs = null
        return
    }
    def b
    try { b = atomicState.battery as double } catch (ignored) { return }
    if (b == null) return
    if (b < leaveReadyPct()) {
        atomicState.fullPackSinceMs = null
        return
    }
    def raw = 0
    try { raw = rawEstJobPct() as double } catch (ignored) { raw = 0 }
    if (!estInDoneBand(raw)) {
        atomicState.fullPackSinceMs = null
        return
    }
    def since = atomicState.fullPackSinceMs
    if (since == null) {
        atomicState.fullPackSinceMs = now()
        return
    }
    if ((now() - (since as long)) < chargeSitMs()) return
    noteMapFinished(atomicState.mowHoursWeek)
    atomicState.dockWho = "gate"
    atomicState.chargeHold = false
    atomicState.fullPackSinceMs = null
    log.info "NavimowHA: Sat at leave % with no Navimow start, map finished, who gate"
}

def chargeHold() {
    def st = "${atomicState.mowerState ?: ''}".toLowerCase()
    def s = "${atomicState.haStatus ?: ''}".toLowerCase()
    if (st.contains("mow")) return false
    if (s.contains("charg")) return true
    if (s.contains("return") || st.contains("return")) return true
    if (!(st.contains("dock"))) return false
    if (atomicState.dockWho == "charge") {
        def b
        try { b = atomicState.battery as double } catch (ignored) { return false }
        if (b != null && b < leaveReadyPct()) return true
    }
    return false
}

def inWindow() {
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def firstLight = ss.sunrise.time - pad
    def lastLight = ss.sunset.time + pad
    return now() >= firstLight && now() < lastLight
}

def lightWindowOnly() {
    def v = cfgCatchUpUntilDew
    if (v == true || v == "true" || v == 1 || v == "1") return false
    return true
}

def nextFirstLightMs() {
    def pad = (afterSunsetMin() as int) * 60000L
    def ss = getSunriseAndSunset()
    def first = ss.sunrise.time - pad
    if ((first as long) <= now()) first = sunOn(1).sunrise.time - pad
    return first as long
}

def newJobHeld() {
    if (jobOpen()) return false
    def hold = atomicState.nextJobAfterMs
    if (hold == null) return false
    try { return now() < (hold as long) } catch (ignored) { return false }
}

def lightAllowsWork() {
    if (inWindow()) return true
    if (lightWindowOnly()) return false
    return atomicState.catchUp == true
}

def decide() {
    refreshEstJobPct()
    if (atomicState.jobMowH == null) {
        def e = 0
        try { e = (atomicState.estJobPct ?: 0) as double } catch (ignored) { e = 0 }
        def hpp = hoursPerPass() as double
        if (e > 1 && hpp > 0) atomicState.jobMowH = rnd((e / 100.0) * hpp, 2)
        else atomicState.jobMowH = 0
    }
    atomicState.targetPasses = targetPasses()
    atomicState.hoursPerPass = hoursPerPass()
    def done = 0
    if ((hoursPerPass() as double) > 0) {
        done = ((atomicState.mowHoursWeek ?: 0) as double) / (hoursPerPass() as double)
    }
    atomicState.passesDone = rnd(done, 2)
    atomicState.rainLock = rainLocked()
    if (atomicState.rainLock) atomicState.waitHours = waitHours()
    else atomicState.waitHours = 0
    def leafOk = true
    if (leafDev && sensorLive(leafDev, atomicState.lastLeafMs)) {
        if (atomicState.leafPct != null) leafOk = (atomicState.leafPct as double) <= (cfg("leafDryPct") as int)
    } else if (atomicState.leafPct != null) {
        leafOk = !(atomicState.dewOn) && !(atomicState.raining) && !(atomicState.rainLock)
    }
    def dry = !(atomicState.raining) && !(atomicState.rainLock) && leafOk
    atomicState.lawnDry = dry
    atomicState.inWindow = inWindow()
    noteWeatherDebt()
    def cap = pacedCap(atomicState.targetPasses, done)
    atomicState.pacedCap = cap
    def underCap = done < cap - 0.02
    atomicState.catchUp = underCap || jobOpen()
    atomicState.todayPrecious = todayPrecious()

    def battOk = true
    if (atomicState.battery != null) {
        try { battOk = (atomicState.battery as double) >= packFieldFloor() }
        catch (ignored) { battOk = true }
    }

    def should = (enabled != false) &&
        (forceDock != true) &&
        (serviceHold != true) &&
        dry &&
        lightAllowsWork() &&
        atomicState.catchUp &&
        !atomicState.rainLock &&
        !haStale() &&
        !frostLock() &&
        !newJobHeld()
    def chargeHold = chargeHold()
    atomicState.chargeHold = chargeHold
    def leaveT0 = leaveTarget()
    atomicState.leaveTarget = leaveT0
    inferSitFinished()
    predictChargeMow(leaveT0)
    refreshCoverageAcres()
    def late0 = lateSliceOk(leaveT0)
    atomicState.lateSlice = late0
    atomicState.shouldRun = ((should && !chargeHold) || late0) && !newJobHeld()
    publishStatus()

    def mst = atomicState.mowerState
    if (mst in ["mowing", "returning"]) atomicState.startPending = false
    if (mst == "docked") atomicState.dockPending = false
    if (forceDock && mst in ["mowing", "paused", "returning"]) {
        recallMower()
        return
    }
    if (!enabled) return
    if (serviceHold == true) return
    if (haStale()) {
        def lastS = "${atomicState.lastPayloadState ?: atomicState.prevMowerState ?: ''}"
        if (!lightAllowsWork() && lastS == "mowing") recallMower()
        return
    }
    def leaveT = leaveTarget()
    atomicState.leaveTarget = leaveT
    def late = lateSliceOk(leaveT)
    atomicState.lateSlice = late
    def battReady = battReadyToLeave(leaveT)
    def gateMayStart = atomicState.dockWho != "charge"
    def mayStart = (should && !chargeHold && battReady && battOk && gateMayStart && !newJobHeld()) || (late && !newJobHeld())
    if (mayStart && mst in ["docked", "paused"]) {
        def lastStart = (atomicState.lastHaStartMs ?: 0) as long
        if (atomicState.startPending == true && (now() - lastStart) < 600000L) {
            // wait until HA shows mowing, up to 10 min
        } else if (now() - lastStart >= 180000L) {
            atomicState.lastHaStartMs = now()
            atomicState.startPending = true
            haService("homeassistant", "update_entity")
            haService("lawn_mower", "start_mowing")
            atomicState.dockWho = "charge"
            noteFirstSliceStart(atomicState.lastHaStartMs)
            atomicState.outingBy = "gate"
        }
    } else if (hubitatShouldRecall() && !late && mst in ["mowing", "paused", "returning"]) {
        recallMower()
    }
}

def hubitatShouldRecall() {
    if (forceDock == true) return true
    if (!lightAllowsWork()) return true
    if (frostLock()) return true
    if (atomicState.raining == true || atomicState.rainLock == true) return true
    if (atomicState.lawnDry != true) return true
    return false
}

def payloadTimeMs(json) {
    def raw = json?.last_updated ?: json?.last_changed
    if (!raw) return now()
    def s = "${raw}"
    def fmts = [
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    ]
    for (f in fmts) {
        try { return Date.parse(f, s).time } catch (ignored) {}
    }
    return now()
}

def backfillWindowH() {
    def avg = null
    try {
        if (atomicState.avgMowSliceH != null) avg = atomicState.avgMowSliceH as double
    } catch (ignored) { avg = null }
    if (avg == null || avg < 0.20d) return null
    def hi = avg * 1.5d
    return [lo: 0.0d, hi: hi]
}

def maybeBackfillMissedMow(st) {
    if (atomicState.startPending != true) return null
    if (!(st in ["mowing", "returning", "docked"])) return null
    def startT = atomicState.lastHaStartMs
    if (!startT) return null
    if (atomicState.startBackfillOf == startT) return null
    def elapsed = (now() - (startT as long)) / 3600000.0d
    def w = backfillWindowH()
    if (!w) return null
    if (elapsed <= 0.0d || elapsed > (w.hi as double)) return null
    atomicState.startBackfillOf = startT
    noteFirstSliceStart(startT)
    log.info "NavimowHA: Start seen " + d2(elapsed) + "h late, gap stays the old state"
    return null
}

def creditMowerHours(st, payloadT) {
    maybeBackfillMissedMow(st)
    if (payloadT) atomicState.lastPayloadMs = payloadT
    def prevT = atomicState.lastHourCreditMs
    def prevS = atomicState.lastPayloadState
    atomicState.lastHourCreditMs = now()
    if (prevT == null || !prevS) {
        atomicState.lastPayloadState = st
        atomicState.lastGapH = 0
        atomicState.lastGapKind = "none"
        return
    }
    def gapH = (now() - (prevT as long)) / 3600000.0
    if (gapH > 24) gapH = 24
    if (gapH <= 0) {
        atomicState.lastPayloadState = st
        atomicState.lastGapH = 0
        atomicState.lastGapKind = "none"
        return
    }
    def capped = false
    atomicState.lastGapH = rnd(gapH, 2)
    if (serviceHold == true) {
        if (prevS == "mowing") {
            def cutH = (atomicState.mowSliceH ?: 0) as double
            if (cutH > 0) {
                atomicState._sliceWhyOverride = "service"
                noteSlicePct(cutH)
                atomicState._sliceWhyOverride = null
                atomicState.mowSliceH = 0
                log.info "NavimowHA: Slice cut short by service " + d2(cutH) + "h"
            }
        }
        atomicState.dockHoursWeek = ((atomicState.dockHoursWeek ?: 0) as double) + gapH
        atomicState.serviceHoursWeek = ((atomicState.serviceHoursWeek ?: 0) as double) + gapH
        atomicState.serviceHoursLife = ((atomicState.serviceHoursLife ?: 0) as double) + gapH
        atomicState.lastPayloadState = st
        atomicState.lastGapKind = capped ? "service capped" : "service"
        return
    }
    if (prevS == "mowing" && atomicState.dockPending == true) {
        atomicState.returnHoursWeek = ((atomicState.returnHoursWeek ?: 0) as double) + gapH
        atomicState.returnHoursLife = ((atomicState.returnHoursLife ?: 0) as double) + gapH
        atomicState.lastGapKind = "dock pending"
    } else if (prevS == "mowing") {
        atomicState.mowHoursWeek = ((atomicState.mowHoursWeek ?: 0) as double) + gapH
        atomicState.mowHoursLife = ((atomicState.mowHoursLife ?: 0) as double) + gapH
        if (serviceHold != true) atomicState.serviceMowH = ((atomicState.serviceMowH ?: 0) as double) + gapH
        atomicState.bladeMowH = ((atomicState.bladeMowH ?: 0) as double) + gapH
        atomicState.jobMowH = ((atomicState.jobMowH ?: 0) as double) + gapH
        atomicState.mowSliceH = ((atomicState.mowSliceH ?: 0) as double) + gapH
        atomicState.lastGapKind = capped ? "mow capped" : "mow"
        if (st != "mowing") {
            noteSlicePct(atomicState.mowSliceH)
            if (navimowOwnedOuting()) {
                noteMowSlice(atomicState.mowSliceH)
                log.info "NavimowHA: Mow slice " + d2(atomicState.mowSliceH) + "h avg " + d2(atomicState.avgMowSliceH)
            } else {
                log.info "NavimowHA: Mow slice " + d2(atomicState.mowSliceH) + "h " + sliceWhy() + ", hours not averaged"
            }
            atomicState.mowSliceH = 0
        }
    } else if (prevS == "returning") {
        atomicState.returnHoursWeek = ((atomicState.returnHoursWeek ?: 0) as double) + gapH
        atomicState.returnHoursLife = ((atomicState.returnHoursLife ?: 0) as double) + gapH
        atomicState.returnSliceH = ((atomicState.returnSliceH ?: 0) as double) + gapH
        atomicState.lastGapKind = capped ? "return capped" : "return"
        if (st != "returning") {
            noteReturnTrip(atomicState.returnSliceH)
            log.info "NavimowHA: Return trip " + d2(atomicState.returnSliceH) + "h week " + d2(atomicState.returnHoursWeek)
            atomicState.returnSliceH = 0
        }
    } else if (prevS == "docked") {
        atomicState.dockHoursWeek = ((atomicState.dockHoursWeek ?: 0) as double) + gapH
        def charging = false
        if (atomicState.chargeSliceOn == true && atomicState.chargeNoted != true && !packAtLeave()) charging = true
        else {
            def hs = "${atomicState.haStatus ?: ''}".toLowerCase()
            if (hs.contains("charg") && !packAtLeave()) charging = true
        }
        if (charging) {
            atomicState.chargeHoursWeek = ((atomicState.chargeHoursWeek ?: 0) as double) + gapH
            atomicState.chargeHoursLife = ((atomicState.chargeHoursLife ?: 0) as double) + gapH
            atomicState.lastGapKind = capped ? "charge capped" : "charge"
        } else {
            atomicState.dockIdleHoursWeek = ((atomicState.dockIdleHoursWeek ?: 0) as double) + gapH
            atomicState.dockIdleHoursLife = ((atomicState.dockIdleHoursLife ?: 0) as double) + gapH
            atomicState.lastGapKind = capped ? "idle capped" : "idle"
        }
        if (atomicState.chargeSliceOn == true && atomicState.chargeNoted != true) {
            atomicState.chargeSliceH = ((atomicState.chargeSliceH ?: 0) as double) + gapH
            if (packAtLeave()) {
                if (navimowOwnedOuting()) {
                    noteChargeSit(atomicState.chargeSliceH)
                    log.info "NavimowHA: Charge sit " + d2(atomicState.chargeSliceH) + "h avg " + d2(atomicState.avgChargeH)
                } else {
                    log.info "NavimowHA: Charge sit " + d2(atomicState.chargeSliceH) + "h gate, not averaged"
                }
                atomicState.chargeNoted = true
            }
        }
        if (st == "mowing") {
            if (atomicState.chargeSliceOn == true && atomicState.chargeNoted != true) {
                if (navimowOwnedOuting()) {
                    noteChargeSit(atomicState.chargeSliceH)
                    log.info "NavimowHA: Charge sit " + d2(atomicState.chargeSliceH) + "h avg " + d2(atomicState.avgChargeH)
                } else {
                    log.info "NavimowHA: Charge sit " + d2(atomicState.chargeSliceH) + "h gate, not averaged"
                }
            }
            atomicState.chargeSliceOn = false
            atomicState.chargeSliceH = 0
            atomicState.chargeNoted = false
        }
    } else {
        atomicState.lastGapKind = "dropped"
    }
    if (st == "returning" && prevS != "returning") atomicState.returnSliceH = 0
    if (st == "mowing" && prevS != "mowing") atomicState.mowSliceH = 0
    if (st == "docked" && prevS in ["mowing", "returning"]) {
        atomicState.chargeSliceOn = true
        atomicState.chargeSliceH = 0
        atomicState.chargeNoted = false
    }
    atomicState.lastPayloadState = st
}

def weekElapsedH() {
    def start = mondayStartMs()
    try {
        def first = atomicState.firstLiveMs as long
        if (first && first > start) start = first
    } catch (ignored) {}
    def h = (now() - start) / 3600000.0d
    if (h < 0) h = 0
    if (h > 168.0d) h = 168.0d
    return h
}

def weekBucketSum() {
    def s = 0.0d
    try { s += (atomicState.mowHoursWeek ?: 0) as double } catch (ignored) {}
    try { s += (atomicState.returnHoursWeek ?: 0) as double } catch (ignored) {}
    try { s += (atomicState.chargeHoursWeek ?: 0) as double } catch (ignored) {}
    try { s += (atomicState.dockIdleHoursWeek ?: 0) as double } catch (ignored) {}
    try { s += (atomicState.serviceHoursWeek ?: 0) as double } catch (ignored) {}
    return s
}

def weekHourRatio() {
    def e = weekElapsedH()
    if (e < 0.02d) return 1.0d
    return weekBucketSum() / e
}

def weekHourOff() {
    return weekHourRatio() - 1.0d
}

def pollAgeText() {
    def t = atomicState.lastHaOk ?: atomicState.lastHourCreditMs
    if (!t) return "?"
    def min = (now() - (t as long)) / 60000.0
    if (min < 0) min = 0
    return "${rnd(min, 1)} min"
}

def payloadAgeText() {
    def t = atomicState.lastPayloadMs ?: atomicState.lastHaOk
    if (!t) return "?"
    def min = (now() - (t as long)) / 60000.0
    if (min < 0) min = 0
    return "${rnd(min, 1)} min"
}

def lastGapText() {
    def g = atomicState.lastGapH
    def k = atomicState.lastGapKind ?: "none"
    if (g == null) return k
    return "${d2(g)} h ${k}"
}

def msClock(t) {
    if (!t) return "—"
    def n
    try { n = t as long } catch (ignored) { return "—" }
    if (n < 100000) return "—"
    try { return new Date(n).format("EEE h:mm a") } catch (ignored) { return "${n}" }
}

def firstLiveText() {
    def t = atomicState.firstLiveMs
    if (!t) return "?"
    try { return new Date(t as long).format("yyyy-MM-dd HH:mm") } catch (ignored) { return "${t}" }
}

def todayRainSoon() {
    def pop = atomicState.forecastRainPct
    if (pop == null) return false
    try { return (pop as double) >= (cfg("forecastRainPct") as int) } catch (ignored) { return false }
}

def todayPrecious() {
    if (enabled == false || forceDock == true || serviceHold == true) return false
    if (haStale() || frostLock()) return false
    if (!(atomicState.lawnDry) || !(atomicState.inWindow)) return false
    def rem = remainingLightH()
    def minH = cfg("lateMinLightH") as double
    if (rem < minH) return false
    def sliceH = hoursPerPass() as double
    def packH = atomicState.mowHoursLeft
    if (packH != null && (packH as double) > 0 && (packH as double) < sliceH) sliceH = packH as double
    if (sliceH < minH) sliceH = minH
    if (rem < sliceH) return true
    return todayRainSoon()
}

def noteWeatherDebt() {
    def today
    try { today = new Date().format("yyyy-MM-dd", location.timeZone) } catch (ignored) { today = "${new Date()}" }
    if (atomicState.debtDay != today) {
        atomicState.weatherDebtH = 0
        atomicState.debtDay = today
        atomicState.lastDebtMs = now()
        return
    }
    def last = (atomicState.lastDebtMs ?: now()) as long
    atomicState.lastDebtMs = now()
    def dt = (now() - last) / 3600000.0
    if (dt <= 0 || dt > 0.5) return
    if (atomicState.debtCarryH == null) atomicState.debtCarryH = atomicState.weatherDebtH ?: 0
    if (atomicState.inWindow == true && atomicState.lawnDry == false) {
        atomicState.weatherDebtH = rnd(((atomicState.weatherDebtH ?: 0) as double) + dt, 2)
        atomicState.debtCarryH = rnd(((atomicState.debtCarryH ?: 0) as double) + dt, 2)
    }
}

def remainingLightH() {
    def ss = getSunriseAndSunset()
    def pad = (afterSunsetMin() as int) * 60000L
    def last = ss.sunset.time + pad
    def h = (last - now()) / 3600000.0
    if (h < 0) h = 0
    return h
}

def remainingDewH() {
    def dew = eveningDewOnMs()
    if (dew == null) return 99.0d
    def h = (dew - now()) / 3600000.0
    if (h < 0) h = 0
    return h
}

def remainingWorkH() {
    if (!lightWindowOnly() && atomicState.catchUp == true) return remainingDewH()
    return remainingLightH()
}

def chargePctPerHour() {
    def r = atomicState.chargePctPerHour
    if (r != null) {
        try {
            def n = r as double
            if (n >= 10 && n <= 80) return n
        } catch (ignored) {}
    }
    return 0.0d
}

def mowPctPerHour() {
    def r = atomicState.mowPctPerHour
    if (r != null) {
        try {
            def n = r as double
            if (n >= 10 && n <= 80) return n
        } catch (ignored) {}
    }
    return 0.0d
}

def packFieldFloor() {
    def r = 15
    try { r = returnTarget() as double } catch (ignored) {}
    return (r as double) + 10.0d
}

def returnTarget() {
    def lo = cfg("returnMin") as double
    def hi = cfg("returnMax") as double
    def avg = atomicState.avgReturnBatt
    if (avg != null) {
        try {
            def n = avg as double
            if (n >= lo && n <= hi) return n
        } catch (ignored) {}
    }
    return cfg("returnSeed") as double
}

def predictChargeMow(leaveT) {
    def batt = atomicState.battery
    def chg = chargePctPerHour()
    def drn = mowPctPerHour()
    def ret = returnTarget() as double
    def tgt = (leaveT ?: 80) as double
    def toFull = null
    def runH = null
    if (batt != null && chg > 0 && drn > 0) {
        try {
            def b = batt as double
            if (chg > 0) {
                def h = (tgt - b) / chg
                if (h < 0) h = 0
                toFull = rnd(h, 2)
            }
            if (drn > 0) {
                def h2 = (b - ret) / drn
                if (h2 < 0) h2 = 0
                runH = rnd(h2, 2)
            }
        } catch (ignored) {}
    }
    atomicState.hoursToFull = toFull
    atomicState.mowHoursLeft = runH
}

def lateSliceOk(leaveT) {
    def makeup = (!lightWindowOnly() && atomicState.catchUp == true)
    def rem0 = remainingWorkH()
    def minH = cfg("lateMinLightH") as double
    if (rem0 > 2.0 || rem0 < minH) {
        atomicState.sliceNeedBatt = null
        atomicState.sliceHours = null
        return false
    }
    if (enabled == false || forceDock == true || serviceHold == true) return false
    if (!(atomicState.lawnDry)) return false
    if (!makeup && !(atomicState.inWindow)) return false
    if (frostLock() || haStale()) return false
    if (!makeup && atomicState.dockWho != "charge" && !todayPrecious()) return false
    if (atomicState.battery == null) return false
    def batt
    try { batt = atomicState.battery as double } catch (ignored) { return false }
    def ret = returnTarget() as double
    def rem = rem0
    def chg = chargePctPerHour()
    def drn = mowPctPerHour()
    if (chg <= 0 || drn <= 0) {
        atomicState.sliceNeedBatt = null
        atomicState.sliceHours = null
        return false
    }
    def arrive = ret + (cfg("lateArriveBuf") as double)
    def need = arrive + (drn * rem)
    if (need > 100) {
        atomicState.sliceNeedBatt = 100
        atomicState.sliceHours = null
        return false
    }
    atomicState.sliceNeedBatt = rnd(need, 0)
    if (batt < need) return false
    def toFull = ((leaveT as double) - batt) / chg
    if (toFull < 0) toFull = 0
    def runH = rem
    atomicState.sliceHours = rnd(runH, 2)
    return toFull > rem && runH >= minH
}

def leaveTarget() {
    def lo = cfg("leaveMin") as double
    def hi = cfg("leaveMax") as double
    def avg = atomicState.avgLeaveBatt
    if (avg != null) {
        try {
            def n = avg as double
            if (n >= lo && n <= hi) return rnd(n, 0)
        } catch (ignored) {}
    }
    return cfg("leaveSeed") as double
}

def battReadyToLeave(tgt) {
    if (atomicState.battery == null) return true
    try {
        return (atomicState.battery as double) >= leaveReadyPct()
    } catch (ignored) {
        return true
    }
}

def rawEstJobPct() {
    def mapped = (lawnAcres ?: 1.00) as double
    def rate = cutRate()
    def hpp = (rate > 0) ? (mapped / rate) : 10.27
    def base = (atomicState.jobHoursBase ?: 0) as double
    def hrs = ((atomicState.mowHoursWeek ?: 0) as double) - base
    if (hrs < 0) hrs = 0
    if (hpp <= 0) return 0
    def pct = hrs / hpp * 100.0
    if (pct > 200) pct = 200
    return rnd(pct, 1)
}

def snapPct() {
    try {
        if (atomicState.calPct != null) return atomicState.calPct as double
    } catch (ignored) {}
    return 0.0d
}

def doneBand() {
    def snap = snapPct()
    if (snap > 50) return [lo: 90.0d, hi: 110.0d]
    return [lo: 80.0d, hi: 120.0d]
}

def doneBandText() {
    def b = doneBand()
    return "${b.lo}–${b.hi}"
}

def estInDoneBand(est) {
    def e = 0
    try { e = est as double } catch (ignored) { return false }
    def b = doneBand()
    return e >= (b.lo as double) && e <= (b.hi as double)
}

def noteDockWho(was, st, batt) {
    if (st != "docked") return
    def est = 0
    try { est = rawEstJobPct() as double } catch (ignored) { est = 0 }
    def fromCut = (was in ["mowing", "returning"])
    if (fromCut && atomicState.dockPending == true) {
        atomicState.dockWho = "gate"
        log.info "NavimowHA: Hubitat dock " + batt + "% who gate"
        return
    }
    if (atomicState.dockWho in ["done", "gate"]) return
    if (fromCut) {
        atomicState.dockWho = "charge"
        noteDoneSitH(batt)
        log.info "NavimowHA: Return " + batt + "% who charge, sit " + (atomicState.doneSitH ?: 0.25) + "h after leave"
    }
}

def recallMower() {
    if (atomicState.mowerState == "docked") {
        atomicState.dockPending = false
        return
    }
    def lastDock = (atomicState.lastHaDockMs ?: 0) as long
    if (atomicState.dockPending == true && (now() - lastDock) < 600000L) return
    if (now() - lastDock < 180000L) return
    atomicState.lastHaDockMs = now()
    atomicState.dockPending = true
    atomicState.dockWho = "gate"
    haService("lawn_mower", "dock")
}

def haService(String domain, String service) {
    def params = [
        uri: haBase() + "/api/services/${domain}/${service}",
        headers: [
            "Authorization": "Bearer ${haToken}",
            "Content-Type": "application/json"
        ],
        body: groovy.json.JsonOutput.toJson([entity_id: mowerEntity]),
        timeout: 20
    ]
    logDbg "HA ${domain}.${service} ${mowerEntity}"
    asynchttpPost("gotService", params, [svc: "${domain}.${service}"])
}

def gotService(resp, data) {
    if (resp.hasError()) {
        markHaFail("HA ${data?.svc}: ${resp.getErrorMessage()}")
    } else {
        atomicState.haFails = 0
        atomicState.lastHaOk = now()
        logDbg "HA ${data?.svc} ok"
    }
}

def markHaFail(msg) {
    atomicState.lastError = msg
    atomicState.haFails = ((atomicState.haFails ?: 0) as int) + 1
    log.warn msg
}

def creditStaleHours() {
    def st = atomicState.lastPayloadState ?: atomicState.prevMowerState
    if (!st || st in ["unavailable", "unknown", "null", ""]) return
    creditMowerHours(st, now())
}

def frostLock() {
    if (atomicState.tempF == null) return false
    try {
        return (atomicState.tempF as double) <= 36.0d
    } catch (ignored) {
        return false
    }
}

def haStale() {
    def fails = (atomicState.haFails ?: 0) as int
    if (fails >= 3) return true
    def st = "${atomicState.mowerState ?: ''}"
    if (st in ["unavailable", "unknown", "null", ""]) return true
    def last = (atomicState.lastHaOk ?: 0) as long
    if (!last) return false
    def mins = 12
    try { mins = cfg("staleMin") as int } catch (ignored) {}
    if (mins < 6) mins = 12
    if ((now() - last) > mins * 60 * 1000L) return true
    return false
}

def mqttStale() {
    if (haStale()) return false
    def stamp = atomicState.lastPayloadMs
    if (!stamp) return false
    def ageMin = (now() - (stamp as long)) / 60000.0
    return ageMin > 15
}

def daysLeftInMonWeek() {
    def cal = Calendar.getInstance()
    try { cal.setTimeZone(location.timeZone) } catch (ignored) {}
    def dow = cal.get(Calendar.DAY_OF_WEEK)
    if (dow == Calendar.SUNDAY) return 0
    return 8 - dow
}

def csvNums(raw) {
    if (!raw) return []
    return "${raw}".tokenize(",").collect { toNum(it?.trim()) }
}

def wmoWet(code) {
    if (code == null) return false
    def c = code as int
    if (c >= 51 && c <= 67) return true
    if (c >= 80 && c <= 82) return true
    if (c >= 95 && c <= 99) return true
    return false
}

def dayLost(i, pops, qpfs, codes) {
    def pop = (i < pops.size()) ? pops[i] : null
    def qpf = (i < qpfs.size()) ? qpfs[i] : null
    def code = (i < codes.size()) ? codes[i] : null
    def need = cfg("forecastRainPct") as int
    def inch = 0.15d
    if (qpf != null && (qpf as double) >= inch) return true
    if (pop != null && (pop as double) >= need && wmoWet(code)) return true
    if (pop != null && (pop as double) >= need && (code == null)) return true
    return false
}

def lateWeekLost(dev) {
    def pops = csvNums(attr(dev, ["forecastPops"]))
    def qpfs = csvNums(attr(dev, ["forecastQpf"]))
    def codes = csvNums(attr(dev, ["forecastCodes"]))
    def maxp = null
    def lost = false
    def n = pops.size()
    if (n < 2) n = Math.max(qpfs.size(), codes.size())
    for (int i = 1; i < n; i++) {
        def p = (i < pops.size()) ? pops[i] : null
        if (p != null) maxp = dmax(maxp != null ? maxp : 0, p)
        if (dayLost(i, pops, qpfs, codes)) lost = true
    }
    return [maxPop: maxp, lost: lost]
}

def dryDaysLeft() {
    def pops = csvNums(attr(wxDev, ["forecastPops"]))
    def qpfs = csvNums(attr(wxDev, ["forecastQpf"]))
    def codes = csvNums(attr(wxDev, ["forecastCodes"]))
    def left = daysLeftInMonWeek()
    def n = 0
    for (int i = 0; i <= left; i++) {
        if (!dayLost(i, pops, qpfs, codes)) n++
    }
    if (n < 1) n = 1
    return n
}

def refreshCoverageAcres() {
    def rate = 0
    try { rate = cutRate() as double } catch (ignored) { rate = 0 }
    def h = (atomicState.mowHoursWeek ?: 0) as double
    def acres = rate * h
    def mapped = (lawnAcres ?: 1.00) as double
    if (mapped < 0.1) mapped = 0.1
    if (acres > mapped * 8) acres = mapped * 8
    if (acres < 0) acres = 0
    atomicState.coverageAcres = rnd(acres, 2)
}

def readForecastChance(dev) {
    def pops = csvNums(attr(dev, ["forecastPops"]))
    def pct = null
    if (pops && pops[0] != null) {
        try { pct = (pops[0] as int) } catch (ignored) { pct = null }
    }
    def names = [
        "precipitationProbabilityToday", "Today_ChanceRain", "forecastPop",
        "precipChanceDay1", "percentPrecip"
    ]
    if (pct == null) pct = firstNum(dev, names)
    def texts = []
    ["forecast", "weather", "condition", "forecastShort", "forecastToday",
     "weatherTomorrow", "Today_DetailedForecast", "Tomorrow_DetailedForecast",
     "Today_ShortForecast", "condition_text", "summarymessage"].each { n ->
        def v = attr(dev, [n])
        if (v) texts << "${v}"
    }
    def blob = texts.join(" ")
    if (pct == null && blob) {
        def m = blob =~ /(?i)(?:chance of (?:rain|precip(?:itation)?)|precip(?:itation)? chance|pop)[^\d]{0,12}(\d{1,3})\s*%/
        if (m.find()) pct = m.group(1) as int
        else {
            def m2 = blob =~ /(\d{1,3})\s*%\s*(?:chance of rain|precip)/
            if (m2.find()) pct = m2.group(1) as int
        }
    }
    try {
        def att = dev.getSupportedAttributes()
        def listed = att?.collect { it.name }?.join(", ")
        atomicState.wxAttrNames = listed
        if (pct == null && att) {
            att.each { a ->
                def nm = "${a.name}".toLowerCase()
                if (nm.find(/chance|probability|pop|precipChance|chanceOfRain/) && !nm.find(/rate|sum|inches/)) {
                    def v = toNum(attr(dev, [a.name]))
                    if (v != null && v >= 0 && v <= 100) {
                        if (pct == null || v > pct) pct = v
                    }
                }
            }
        }
    } catch (ignored) {}
    return [pct: pct, text: blob]
}

def firstNum(dev, names) {
    for (n in names) {
        def v = toNum(attr(dev, [n]))
        if (v != null) return v
    }
    return null
}

def toNum(val) {
    if (val == null) return null
    try { return val as double } catch (ignored) { return null }
}

def toF(val) {
    if (val == null) return null
    def t
    try { t = val as double } catch (ignored) { return null }
    return rnd(t, 1)
}

def dewFromRh(tempF, rh) {
    if (tempF == null || rh == null) return null
    def r
    try { r = rh as double } catch (ignored) { return null }
    if (r <= 0) return null
    def tc = ((tempF as double) - 32) * 5 / 9
    def a = 17.62
    def b = 243.12
    def g = (a * tc) / (b + tc) + Math.log(r / 100.0)
    def tdc = (b * g) / (a - g)
    return rnd((tdc * 9 / 5) + 32, 1)
}

def edgeClock(ms) {
    if (!ms) return "—"
    try { return fmtClock(ms as long) } catch (ignored) { return "—" }
}

def localDayKey() {
    return new Date(now()).format("yyyy-MM-dd", location.timeZone)
}

def noteFirstSliceStart(ms) {
    if (!ms) return
    def t
    try { t = ms as long } catch (ignored) { return }
    if (t <= 0) return
    def day = new Date(t).format("yyyy-MM-dd", location.timeZone)
    if (atomicState.firstSliceDay == day && atomicState.firstSliceStartMs) return
    atomicState.firstSliceDay = day
    atomicState.firstSliceStartMs = t
    log.info "NavimowHA: First slice " + edgeClock(t)
}

def stampMowEdges(was, st) {
    if (!was || was == st) return
    if (was == "docked" && st == "mowing") {
        atomicState.lastMowStartMs = now()
        noteFirstSliceStart(atomicState.lastMowStartMs)
        def gateStart = false
        try {
            def t0 = (atomicState.lastHaStartMs ?: 0) as long
            if (atomicState.outingBy == "gate" && t0 && (now() - t0) < 600000L) gateStart = true
        } catch (ignored) {}
        if (!gateStart) atomicState.outingBy = "charge"
        logDbg "Mow start " + edgeClock(atomicState.lastMowStartMs) + " batt " + atomicState.battery + " who " + atomicState.dockWho + " by " + atomicState.outingBy
    }
    if (was == "mowing" && st == "returning") {
        snapHaProgressBeforeDock()
        log.info "NavimowHA: Edge mowing → returning batt " + atomicState.battery
    }
    if (was == "returning" && st == "docked") {
        atomicState.lastMowDockMs = now()
        log.info "NavimowHA: Edge returning → docked batt " + atomicState.battery + " who " + atomicState.dockWho + " est " + rawEstJobPct() + "%"
    }
    if (was == "mowing" && st == "docked") {
        snapHaProgressBeforeDock()
        atomicState.lastMowDockMs = now()
        log.info "NavimowHA: Mow dock " + edgeClock(atomicState.lastMowDockMs) + " batt " + atomicState.battery + " who " + atomicState.dockWho + " est " + rawEstJobPct() + "%"
    }
}

def logDbg(msg) {
    if (debugLog == true) log.info "NavimowHA: ${msg}"
}