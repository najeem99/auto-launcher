package com.najib.carwelcome.drive

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.najib.carwelcome.drive.DriveUi.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The fuel part of the phone dashboard: what you spent on petrol in the period on
 * screen, cost per km, a monthly spend chart, your fill-ups, and this month's UAE
 * prices (downloaded, or typed in by hand).
 */
class FuelSection(private val activity: Activity, private val onChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ctx get() = activity

    val view: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val spendCard = DriveUi.card(activity)
    private val priceCard = DriveUi.card(activity)

    private var lastArgs: Args? = null
    private class Args(val name: String, val start: Long, val end: Long, val trips: List<Trip>)

    init {
        view.addView(DriveUi.label(activity, "Fuel").apply { setPadding(dp(context, 4f), dp(context, 22f), 0, 0) })
        view.addView(spendCard)
        view.addView(priceCard)
        drawPrices()
    }

    /** Called when the screen opens: fetch new prices if this month's are missing. */
    fun onResume() {
        FuelPrices.refreshIfStale(activity) { ok -> if (ok) main.post { if (!activity.isFinishing) { drawPrices(); onChanged() } } }
        drawPrices()
    }

    /** Draws the spend card for the period on screen. [trips] are that period's trips. */
    fun show(periodName: String, start: Long, end: Long, trips: List<Trip>) {
        lastArgs = Args(periodName, start, end, trips)
        val rated = TripPrefs(activity).ratedKmpl
        // Six months of spend for the chart, ending with the month the period ends in.
        val months = (5 downTo 0).map { back ->
            val c = Calendar.getInstance().apply {
                timeInMillis = (end - 1).coerceAtMost(System.currentTimeMillis())
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, -back)
            }
            val from = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            from to c.timeInMillis
        }
        io.execute {
            val db = FuelDb.get(activity)
            val fills = runCatching { db.between(start, end) }.getOrDefault(emptyList())
            val everything = runCatching { db.all() }.getOrDefault(emptyList())
            val monthTrips = runCatching { TripDb.get(activity).tripsBetween(months.first().first, months.last().second) }.getOrDefault(emptyList())
            val bars = months.map { (from, to) ->
                val spent = everything.filter { it.at in from until to }.sumOf { it.aed }
                val est = monthTrips.filter { it.startAt in from until to }.sumOf { Fuel.cost(activity, it, rated) }
                Triple(from, spent, est)
            }
            main.post {
                if (activity.isFinishing || lastArgs?.start != start) return@post
                drawSpend(periodName, trips, fills, everything, bars)
            }
        }
    }

    private fun drawSpend(periodName: String, trips: List<Trip>, fills: List<FillUp>, everything: List<FillUp>, bars: List<Triple<Long, Double, Double>>) {
        val c = spendCard
        c.removeAllViews()
        val rated = TripPrefs(ctx).ratedKmpl
        val km = trips.sumOf { it.distanceM } / 1000
        val estLitres = trips.sumOf { Fuel.litres(it, rated) }
        val estAed = trips.sumOf { Fuel.cost(ctx, it, rated) }
        val spent = fills.sumOf { it.aed }
        val litres = fills.sumOf { it.litres }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val real = fills.isNotEmpty()
        left.addView(DriveUi.text(ctx, if (real) "Spent on petrol · $periodName" else "Petrol, estimated · $periodName", 13f, DriveUi.ink3, "medium"))
        val big = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        big.addView(DriveUi.text(ctx, if (real) "AED " else "≈ AED ", 16f, DriveUi.ink2, "medium").apply { setPadding(0, 0, 0, dp(context, 8f)) })
        big.addView(DriveUi.text(ctx, money(if (real) spent else estAed), 44f, DriveUi.sand, "display"))
        left.addView(big, params(4f))
        left.addView(DriveUi.text(ctx, if (real) "${fills.size} ${if (fills.size == 1) "fill-up" else "fill-ups"} · %.1f L · avg AED %.2f/L".format(litres, if (litres > 0) spent / litres else 0.0)
            else "From your trips: %.1f L. Add fill-ups for the real figure.".format(estLitres), 13f, DriveUi.ink2)
            .apply { setPadding(0, dp(context, 4f), 0, 0) })
        head.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(head)
        c.addView(pill("+ Add fill-up", DriveUi.sand, DriveUi.night1) { editFillUp(null) }, params(12f))

        val perKm = when {
            real && km > 5 -> spent / km
            km > 0.5 -> estAed / km
            else -> 0.0
        }
        val odoKmpl = FuelDb.odometerEconomy(everything)
        val now = System.currentTimeMillis()
        val grade = FuelPrices.grade(ctx)
        c.addView(DriveUi.grid(ctx, listOf(
            DriveUi.stat(ctx, "Cost per km", if (perKm > 0) "%.0f fils".format(perKm * 100) else "–", DriveUi.sand,
                if (real && km > 5) "fill-ups ÷ %.0f km driven".format(km) else "estimated from trips"),
            DriveUi.stat(ctx, "Trips' fuel (est.)", if (estAed > 0) "AED ${money(estAed)}" else "–",
                sub = "%.1f L over %.0f km".format(estLitres, km)),
            DriveUi.stat(ctx, "Real economy", odoKmpl?.let { "%.1f km/L".format(it) } ?: "–",
                sub = if (odoKmpl != null) "from odometer, full to full" else "needs 2 full fills with odometer"),
            DriveUi.stat(ctx, "Price now", "AED %.2f".format(FuelPrices.price(ctx, now)), sub = "${grade.title} per litre"),
        )), params(14f))

        // Monthly spend: real fill-ups where you have them, the trip estimate otherwise.
        val anyReal = bars.any { it.second > 0 }
        val values = FloatArray(bars.size) { i -> (if (anyReal) bars[i].second else bars[i].third).toFloat() }
        val colors = IntArray(bars.size) { i -> if (anyReal) DriveUi.sand else DriveUi.ink3 }
        val labels = bars.map { SimpleDateFormat("MMM", Locale.getDefault()).format(Date(it.first)) }
        c.addView(DriveUi.text(ctx, if (anyReal) "Petrol spend by month (AED)" else "Estimated petrol by month (AED, from trips)", 12f, DriveUi.ink3, "medium")
            .apply { setPadding(0, dp(context, 16f), 0, 0) })
        c.addView(KmChart(ctx).apply { set(values, colors, labels) }, params(8f))

        if (fills.isNotEmpty()) {
            c.addView(DriveUi.text(ctx, "Fill-ups · tap one to change it", 12f, DriveUi.ink3, "medium").apply { setPadding(0, dp(context, 16f), 0, 0) })
            fills.sortedByDescending { it.at }.forEach { c.addView(fillRow(it), params(8f)) }
        }
    }

    private fun fillRow(f: FillUp): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = dp(context, 12f)
        setPadding(p, p, p, p)
        background = DriveUi.rounded(DriveUi.card2, dp(context, 14f).toFloat())
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(DriveUi.text(context, "${DriveUi.day(f.at)} · ${DriveUi.time(context, f.at)}", 14f, DriveUi.ink, "medium"))
        val bits = mutableListOf("%.1f L".format(f.litres), f.grade.title, "AED %.2f/L".format(f.pricePerLitre))
        f.odometerKm?.let { bits += "%,.0f km".format(it) }
        if (!f.full) bits += "part fill"
        left.addView(DriveUi.text(context, bits.joinToString(" · "), 12f, DriveUi.ink3).apply { setPadding(0, dp(context, 4f), 0, 0) })
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(DriveUi.text(context, "AED ${money(f.aed)}", 16f, DriveUi.sand, "display"))
        isClickable = true
        setOnClickListener { editFillUp(f) }
    }

    // ---- Prices -----------------------------------------------------------------------

    private fun drawPrices() {
        val c = priceCard
        c.removeAllViews()
        val now = System.currentTimeMillis()
        val key = FuelPrices.monthKey(now)
        val m = FuelPrices.forMonth(ctx, key)
        val mine = FuelPrices.grade(ctx)
        val exact = FuelPrices.haveMonth(ctx, key)
        c.addView(DriveUi.text(ctx, "UAE fuel prices · ${FuelPrices.monthName(if (exact) key else m?.month ?: key)}", 16f, DriveUi.ink, "display"))
        if (!exact) c.addView(DriveUi.text(ctx, "This month's prices aren't in yet, so last month's are used. Tap Refresh, or Edit to type them in.", 12f, DriveUi.orange)
            .apply { setPadding(0, dp(context, 6f), 0, 0) })
        c.addView(DriveUi.grid(ctx, Grade.values().map { g ->
            val v = m?.prices?.get(g) ?: 0f
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(context, 12f)
                setPadding(p, p, p, p)
                background = if (g == mine) DriveUi.rounded(DriveUi.card2, dp(context, 14f).toFloat(), DriveUi.sand, dp(context, 2f))
                else DriveUi.rounded(DriveUi.card2, dp(context, 14f).toFloat())
                addView(DriveUi.text(context, g.title + if (g == mine) " · mine" else "", 12f, if (g == mine) DriveUi.sand else DriveUi.ink3, "medium"))
                addView(DriveUi.text(context, if (v > 0) "AED %.2f".format(v) else "–", 20f, DriveUi.ink, "display").apply { setPadding(0, dp(context, 6f), 0, 0) })
                isClickable = true
                setOnClickListener { FuelPrices.setGrade(ctx, g); drawPrices(); onChanged() }
            }
        }), params(12f))
        val fetched = FuelPrices.lastFetchAt(ctx)
        val src = when {
            m == null -> "No prices yet."
            m.edited -> "Typed in by you."
            else -> "From ${m.source}."
        }
        val checked = if (fetched > 0) " Checked ${DriveUi.day(fetched)} at ${DriveUi.time(ctx, fetched)}." else ""
        val next = SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(FuelPrices.nextChange(now)))
        c.addView(DriveUi.text(ctx, "$src$checked Prices change on the 1st; next on $next. Tap a grade to set the one you use.", 12f, DriveUi.ink3)
            .apply { setPadding(0, dp(context, 10f), 0, 0) })
        c.addView(row(
            pill("Refresh", DriveUi.card2, DriveUi.ink) {
                toast("Checking prices…")
                FuelPrices.refresh(ctx) { ok -> main.post { toast(if (ok) "Prices updated" else "Couldn't get prices. Check the internet, or tap Edit."); drawPrices(); onChanged() } }
            },
            pill("Edit prices", DriveUi.card2, DriveUi.ink) { editPrices(key) },
        ), params(12f))
    }

    private fun editPrices(key: String) {
        val m = FuelPrices.forMonth(ctx, key)
        val box = dialogBox()
        box.addView(DriveUi.text(ctx, "Prices for ${FuelPrices.monthName(key)}, AED per litre. What you type here is used instead of the downloaded prices.", 13f, DriveUi.ink2))
        val fields = Grade.values().associateWith { g ->
            numberInput(m?.prices?.get(g)?.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "", "0.00").also { box.addView(labelled(g.title, it), params(10f)) }
        }
        val b = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Edit fuel prices")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = fields.mapValues { (_, e) -> e.text.toString().toFloatOrNull() ?: 0f }
                if (p.values.any { it !in 0.5f..20f }) toast("Prices look wrong, not saved")
                else { FuelPrices.setEdit(ctx, key, p); TripBackup.backupSoon(ctx); drawPrices(); onChanged() }
            }
            .setNegativeButton("Cancel", null)
        if (m?.edited == true) b.setNeutralButton("Use downloaded") { _, _ -> FuelPrices.setEdit(ctx, key, null); drawPrices(); onChanged() }
        b.show()
    }

    // ---- Fill-up dialog -------------------------------------------------------------------

    private fun editFillUp(existing: FillUp?) {
        val f = existing?.copy() ?: FillUp(at = System.currentTimeMillis(), grade = FuelPrices.grade(ctx))
        val box = dialogBox()
        val whenText = DriveUi.text(ctx, "", 15f, DriveUi.sand, "medium").apply {
            setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        }
        fun showWhen() { whenText.text = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(f.at)) + " · " + DriveUi.time(ctx, f.at) + "  ✎" }
        showWhen()
        whenText.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = f.at }
            DatePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, y, mo, d ->
                cal.set(y, mo, d)
                TimePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, mi ->
                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi)
                    f.at = cal.timeInMillis.coerceAtMost(System.currentTimeMillis()); showWhen(); hint()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }
        box.addView(whenText)

        val aed = numberInput(if (f.aed > 0) "%.2f".format(f.aed) else "", "e.g. 150")
        val litres = numberInput(if (f.litres > 0) "%.2f".format(f.litres) else "", "worked out")
        val odo = numberInput(f.odometerKm?.let { "%.0f".format(it) } ?: "", "optional", decimal = false)
        val priceLine = DriveUi.text(ctx, "", 12f, DriveUi.ink3)
        box.addView(labelled("Paid (AED)", aed), params(8f))
        box.addView(labelled("Litres", litres), params(8f))
        box.addView(priceLine, params(4f))
        box.addView(labelled("Odometer (km)", odo), params(8f))
        val full = CheckBox(ctx).apply {
            text = "Filled to the top"
            setTextColor(DriveUi.ink)
            isChecked = f.full
        }
        box.addView(full, params(8f))

        // Grade picker: four small pills.
        val grades = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawGrades() {
            grades.removeAllViews()
            Grade.values().forEachIndexed { i, g ->
                grades.addView(DriveUi.text(ctx, g.title.replace(" ", "\n"), 11f, if (g == f.grade) DriveUi.night1 else DriveUi.ink2, "bold").apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(context, 4f), dp(context, 8f), dp(context, 4f), dp(context, 8f))
                    background = DriveUi.rounded(if (g == f.grade) DriveUi.sand else DriveUi.card2, dp(context, 10f).toFloat())
                    setOnClickListener { f.grade = g; drawGrades(); hint() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(ctx, 6f) })
            }
        }
        drawGrades()
        box.addView(grades, params(10f))
        hintFn = {
            val p = FuelPrices.price(ctx, f.at, f.grade)
            val a = aed.text.toString().toDoubleOrNull()
            val l = litres.text.toString().toDoubleOrNull()
            priceLine.text = when {
                a != null && l != null && l > 0 -> "AED %.2f a litre".format(a / l)
                a != null -> "≈ %.2f L at AED %.2f (leave Litres empty to use this)".format(a / p, p)
                l != null -> "≈ AED %.2f at AED %.2f a litre".format(l * p, p)
                else -> "${f.grade.title} is AED %.2f a litre in ${FuelPrices.monthName(FuelPrices.monthKey(f.at))}. Fill in either box.".format(p)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = hint()
        }
        aed.addTextChangedListener(watcher); litres.addTextChangedListener(watcher)
        hint()

        val b = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "Add fill-up" else "Fill-up")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val p = FuelPrices.price(ctx, f.at, f.grade).toDouble()
                var a = aed.text.toString().toDoubleOrNull()?.takeIf { it > 0 }
                var l = litres.text.toString().toDoubleOrNull()?.takeIf { it > 0 }
                if (a == null && l == null) { toast("Type what you paid or the litres"); return@setPositiveButton }
                if (a == null) a = l!! * p
                if (l == null) l = a / p
                if (l > 200 || a > 2000) { toast("That looks too big, not saved"); return@setPositiveButton }
                f.aed = Math.round(a * 100) / 100.0
                f.litres = Math.round(l * 100) / 100.0
                f.odometerKm = odo.text.toString().toDoubleOrNull()?.takeIf { it > 0 }
                f.full = full.isChecked
                io.execute {
                    FuelDb.get(ctx).save(f)
                    TripBackup.backupSoon(ctx)
                    main.post { onChanged() }
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ ->
            io.execute { FuelDb.get(ctx).delete(existing.id); TripBackup.backupSoon(ctx); main.post { onChanged() } }
        }
        b.show()
    }

    private var hintFn: () -> Unit = {}
    private fun hint() = hintFn()

    // ---- Small helpers ------------------------------------------------------------------

    private fun money(v: Double) = if (v >= 100) "%,.0f".format(v) else "%.2f".format(v)

    private fun dialogBox() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(context, 20f)
        setPadding(p, dp(context, 8f), p, 0)
    }

    private fun numberInput(value: String, hint: String, decimal: Boolean = true) = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        setTextColor(DriveUi.ink)
        setHintTextColor(DriveUi.ink3)
        textSize = 16f
        gravity = Gravity.END
        minEms = 4
    }

    private fun labelled(title: String, input: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(DriveUi.text(context, title, 14f, DriveUi.ink2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(input)
    }

    private fun pill(title: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        DriveUi.text(ctx, title, 14f, fg, "bold").apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
            background = DriveUi.rounded(bg, dp(context, 14f).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(ctx, 8f) })
        }
    }

    private fun params(topDp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(ctx, topDp) }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
