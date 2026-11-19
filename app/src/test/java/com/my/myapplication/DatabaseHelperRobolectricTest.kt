package com.my.myapplication

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseHelperRobolectricTest {

    private lateinit var context: Context
    private val dbNames = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        dbNames.forEach { name ->
            runCatching { context.deleteDatabase(name) }
        }
    }

    private fun newHelper(suffix: String): DatabaseHelper {
        val dbName = "test_kakeibo_${suffix}_${System.nanoTime()}.db"
        dbNames.add(dbName)
        return DatabaseHelper(context, dbName)
    }

    @Test
    fun `fresh creation creates v35 tables and default income categories`() {
        val dbh = newHelper("fresh")
        val categories = JSONArray(dbh.getMasterList("categories"))
        val incomeNames = mutableListOf<String>()
        val expenseNames = mutableListOf<String>()
        for (i in 0 until categories.length()) {
            val obj = categories.getJSONObject(i)
            when (obj.optString("cat_type")) {
                "INCOME" -> incomeNames.add(obj.getString("name"))
                "EXPENSE" -> expenseNames.add(obj.getString("name"))
            }
        }
        assertTrue(expenseNames.contains("食費"))
        assertTrue(incomeNames.containsAll(listOf("給与", "賞与", "副収入", "臨時収入")))

        // 新規テーブルが空でもクエリ可能
        assertEquals("[]", dbh.getInputTemplates())
        assertTrue(JSONArray(dbh.getCategoryBudgets("2026-03-01")).length() > 0)
        dbh.close()
    }

    @Test
    fun `upgrade from v34 to v35 preserves existing data and defaults entry_type and cat_type`() {
        val dbName = "test_v34_upgrade_${System.nanoTime()}.db"
        dbNames.add(dbName)

        // v34 DB を事前に構築
        val legacyHelper = object : SQLiteOpenHelper(context, dbName, null, 34) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("CREATE TABLE custom_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("CREATE TABLE point_cards (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("CREATE TABLE cost_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("""
                    CREATE TABLE expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT, price INTEGER, category_id INTEGER, tag_ids TEXT,
                        date TEXT, points INTEGER DEFAULT 0, card_id INTEGER DEFAULT 0,
                        created_at DATETIME DEFAULT (datetime('now','localtime')),
                        updated_at DATETIME, restored_at DATETIME
                    )
                """)
                db.execSQL("CREATE TABLE expense_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, expense_id INTEGER NOT NULL, tag_id INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE action_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, action_type TEXT, timestamp DATETIME, original_data TEXT)")
                db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE fixed_costs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, category_id INTEGER DEFAULT 0, is_active INTEGER DEFAULT 1, created_at DATETIME)")
                db.execSQL("CREATE TABLE budget_periods (period_from TEXT PRIMARY KEY, period_to TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, created_at DATETIME)")
                db.execSQL("CREATE TABLE budget_extras (id INTEGER PRIMARY KEY AUTOINCREMENT, period_from TEXT NOT NULL, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, created_at DATETIME)")
                db.execSQL("CREATE TABLE fixed_cost_history (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, category_id INTEGER DEFAULT 0, valid_from TEXT NOT NULL, valid_to TEXT, created_at DATETIME)")

                db.insert("categories", null, ContentValues().apply { put("name", "食費") })
                db.insert("expenses", null, ContentValues().apply {
                    put("name", "ランチ")
                    put("price", 980)
                    put("category_id", 1)
                    put("tag_ids", "")
                    put("date", "2026-03-10")
                    put("points", 9)
                    put("card_id", 0)
                })
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        legacyHelper.writableDatabase.close()
        legacyHelper.close()

        // v36 DatabaseHelper で開いてマイグレーション実行
        val dbh = DatabaseHelper(context, dbName)
        assertEquals(36, dbh.readableDatabase.version)
        assertEquals(980, dbh.getTotalByRange("2026-03-01", "2026-03-31"))

        val cats = JSONArray(dbh.getMasterList("categories"))
        assertEquals("EXPENSE", cats.getJSONObject(0).getString("cat_type"))
        val hasIncomeCat = (0 until cats.length()).any { cats.getJSONObject(it).getString("cat_type") == "INCOME" }
        assertTrue(hasIncomeCat)

        val recs = JSONArray(dbh.getRecords(0))
        assertEquals(1, recs.length())
        assertEquals("EXPENSE", recs.getJSONObject(0).getString("entry_type"))
        assertEquals("ランチ", recs.getJSONObject(0).getString("name"))
        dbh.close()
    }

    @Test
    fun `importAll preserves v31 and v34 backups without v35 fields and round-trips v35 data`() {
        val dbh = newHelper("import_compat")

        // v31相当バックアップ（entry_type, cat_type, fixed_cost_history, input_templates, category_budgets なし）
        val v31Json = JSONObject().apply {
            put("version", 31)
            put("categories", JSONArray().put(JSONObject().apply { put("id", 1); put("name", "日用品") }))
            put("custom_tags", JSONArray().put(JSONObject().apply { put("id", 1); put("name", "定番") }))
            put("point_cards", JSONArray().put(JSONObject().apply { put("id", 1); put("name", "楽天カード") }))
            put("expenses", JSONArray().put(JSONObject().apply {
                put("id", 10)
                put("name", "洗剤")
                put("price", 450)
                put("category_id", 1)
                put("tag_ids", "1")
                put("date", "2026-02-15")
                put("points", 4)
                put("card_id", 1)
            }))
            put("fixed_costs", JSONArray().put(JSONObject().apply {
                put("id", 1)
                put("name", "家賃")
                put("amount", 65000)
                put("category_id", 0)
                put("is_active", 1)
            }))
            put("budget_periods", JSONArray().put(JSONObject().apply {
                put("period_from", "2026-02-01")
                put("period_to", "2026-02-28")
                put("amount", 100000)
            }))
        }.toString()

        assertEquals("", dbh.importAll(v31Json, "家"))
        assertEquals(450, dbh.getTotalByRange("2026-02-01", "2026-02-28"))
        assertEquals(65000, dbh.getActiveFixedCostHistoryTotal())
        val recs = JSONArray(dbh.getRecords(0))
        assertEquals("EXPENSE", recs.getJSONObject(0).getString("entry_type"))
        val cats = JSONArray(dbh.getMasterList("categories"))
        assertEquals("EXPENSE", cats.getJSONObject(0).getString("cat_type"))

        // v35 機能を追加して exportAll -> importAll ラウンドトリップ
        dbh.saveInputTemplate(JSONObject().apply {
            put("title", "定期チャージ")
            put("name", "Suica")
            put("price", 3000)
            put("category_id", 1)
            put("entry_type", "EXPENSE")
        }.toString())
        dbh.setCategoryBudget("2026-03-01", 1, 12000)

        val exportedV36 = dbh.exportAll("家")
        val exportedObj = JSONObject(exportedV36)
        assertEquals(36, exportedObj.getInt("version"))

        val dbh2 = newHelper("roundtrip")
        assertEquals("", dbh2.importAll(exportedV36, "家"))
        val tpls = JSONArray(dbh2.getInputTemplates())
        assertEquals(1, tpls.length())
        assertEquals("定期チャージ", tpls.getJSONObject(0).getString("title"))
        val cbs = JSONArray(dbh2.getCategoryBudgets("2026-03-01"))
        val cat1Budget = (0 until cbs.length())
            .map { cbs.getJSONObject(it) }
            .first { it.getInt("categoryId") == 1 }
        assertEquals(12000, cat1Budget.getInt("amount"))

        dbh.close()
        dbh2.close()
    }

    @Test
    fun `income and expense are properly isolated in totals, stats, cashflow, and restore`() {
        val dbh = newHelper("cashflow")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val ym = today.substring(0, 7)

        val cats = JSONArray(dbh.getMasterList("categories"))
        val expCatId = (0 until cats.length()).map { cats.getJSONObject(it) }.first { it.getString("cat_type") == "EXPENSE" }.getInt("id")
        val incCatId = (0 until cats.length()).map { cats.getJSONObject(it) }.first { it.getString("cat_type") == "INCOME" }.getInt("id")

        dbh.insertExpense("スーパー", 3200, expCatId, "", today, 32, 0, "EXPENSE")
        dbh.insertExpense("カフェ", 800, expCatId, "", today, 0, 0, "EXPENSE")
        val incId = dbh.insertExpense("3月給与", 280000, incCatId, "", today, 99, 1, "INCOME").toInt()

        // 収入はポイント/カードが強制0で支出集計から除外される
        assertEquals(4000, dbh.getTotal("MONTH"))
        assertEquals(4000, dbh.getTotalByRange("$ym-01", "$ym-31"))

        // キャッシュフロー集計
        val cf = JSONObject(dbh.getCashflowByRange("$ym-01", "$ym-31"))
        assertEquals(4000, cf.getInt("expense"))
        assertEquals(280000, cf.getInt("income"))
        assertEquals(276000, cf.getInt("balance"))

        val monthlyCf = JSONArray(dbh.getMonthlyCashflowStats("$ym-01", "$ym-31", "{}"))
        assertEquals(1, monthlyCf.length())
        assertEquals(276000, monthlyCf.getJSONObject(0).getInt("balance"))

        // カテゴリ統計で EXPENSE / INCOME 分離
        val expCatStats = JSONArray(dbh.getCategoryStats(JSONObject().apply {
            put("dateFrom", "$ym-01")
            put("dateTo", "$ym-31")
            put("entryType", "EXPENSE")
        }.toString()))
        assertEquals(1, expCatStats.length())
        assertEquals(4000, expCatStats.getJSONObject(0).getInt("amount"))

        val incCatStats = JSONArray(dbh.getCategoryStats(JSONObject().apply {
            put("dateFrom", "$ym-01")
            put("dateTo", "$ym-31")
            put("entryType", "INCOME")
        }.toString()))
        assertEquals(1, incCatStats.length())
        assertEquals(280000, incCatStats.getJSONObject(0).getInt("amount"))

        // 削除→復元で entry_type=INCOME が維持される
        dbh.deleteAndLogExpense(incId)
        assertEquals(0, JSONObject(dbh.getCashflowByRange("$ym-01", "$ym-31")).getInt("income"))
        val logs = JSONArray(dbh.getActionLogs("DELETE", 0))
        assertEquals(1, logs.length())
        dbh.restoreLogs(intArrayOf(logs.getJSONObject(0).getInt("id")))
        assertEquals(280000, JSONObject(dbh.getCashflowByRange("$ym-01", "$ym-31")).getInt("income"))

        dbh.close()
    }

    @Test
    fun `templates, category budgets, search summary, calendar summary, and csv export work accurately`() {
        val dbh = newHelper("features")
        val newCatId = dbh.insertCategoryWithType("副業売上", "BOTH").toInt()
        dbh.updateCategoryWithType(newCatId, "副業収支", "BOTH")

        // テンプレート CRUD
        val tplId = dbh.saveInputTemplate(JSONObject().apply {
            put("title", "朝コーヒー")
            put("name", "コーヒー")
            put("price", 350)
            put("category_id", 1)
            put("tag_ids", "1")
            put("card_id", 1)
            put("points", 3)
            put("entry_type", "EXPENSE")
        }.toString()).toInt()
        assertEquals(1, JSONArray(dbh.getInputTemplates()).length())
        dbh.deleteInputTemplate(tplId)
        assertEquals(0, JSONArray(dbh.getInputTemplates()).length())

        // カテゴリ別予算 & コピー
        dbh.setCategoryBudget("2026-02-01", 1, 30000)
        dbh.setCategoryBudget("2026-02-01", newCatId, 5000)
        dbh.copyCategoryBudgets("2026-02-01", "2026-03-01")
        val marBudgets = JSONArray(dbh.getCategoryBudgets("2026-03-01"))
        val copiedAmount = (0 until marBudgets.length())
            .map { marBudgets.getJSONObject(it) }
            .first { it.getInt("categoryId") == 1 }
            .getInt("amount")
        assertEquals(30000, copiedAmount)

        // CSVエスケープ & 数式インジェクション防御
        dbh.insertExpense("=SUM(A1), \"特売\"", 1200, 1, "1", "2026-03-05", 12, 1, "EXPENSE")
        dbh.insertExpense("案件報酬", 50000, newCatId, "", "2026-03-05", 0, 0, "INCOME")

        // 検索サマリー
        val summary = JSONObject(dbh.getSearchSummary(JSONObject().apply {
            put("periodPreset", "CUSTOM")
            put("dateFrom", "2026-03-01")
            put("dateTo", "2026-03-31")
            put("entryType", "ALL")
        }.toString()))
        assertEquals(2, summary.getInt("count"))
        assertEquals(1200, summary.getInt("totalExpense"))
        assertEquals(50000, summary.getInt("totalIncome"))
        assertEquals(48800, summary.getInt("netTotal"))
        assertEquals(12, summary.getInt("totalPoints"))

        // カレンダー月別集計
        val calArr = JSONArray(dbh.getCalendarMonthSummary("2026-03"))
        assertEquals(1, calArr.length())
        assertEquals("2026-03-05", calArr.getJSONObject(0).getString("date"))
        assertEquals(1200, calArr.getJSONObject(0).getInt("expense"))
        assertEquals(50000, calArr.getJSONObject(0).getInt("income"))
        assertEquals(2, calArr.getJSONObject(0).getInt("count"))

        // CSV文字列生成
        val csv = dbh.buildCsvString(JSONObject().apply {
            put("periodPreset", "CUSTOM")
            put("dateFrom", "2026-03-01")
            put("dateTo", "2026-03-31")
        }.toString())
        assertTrue(csv.startsWith("日付,種別,カテゴリ,金額,タグ,メモ,カード/経費タグ,獲得ポイント,登録日時\r\n"))
        assertTrue(csv.contains("\"'=SUM(A1), \"\"特売\"\"\""))
        assertTrue(csv.contains("収入,副業収支,50000"))

        dbh.close()
    }

    @Test
    fun `upgrade from v35 to v36 adds tax, invoice, settlement, due_date columns preserving rows`() {
        val dbName = "test_v35_upgrade_${System.nanoTime()}.db"
        dbNames.add(dbName)

        val legacyHelper = object : SQLiteOpenHelper(context, dbName, null, 35) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, cat_type TEXT NOT NULL DEFAULT 'EXPENSE')")
                db.execSQL("CREATE TABLE custom_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("CREATE TABLE point_cards (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("CREATE TABLE cost_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                db.execSQL("""
                    CREATE TABLE expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT, price INTEGER, category_id INTEGER, tag_ids TEXT,
                        date TEXT, points INTEGER DEFAULT 0, card_id INTEGER DEFAULT 0,
                        entry_type TEXT NOT NULL DEFAULT 'EXPENSE',
                        created_at DATETIME DEFAULT (datetime('now','localtime')),
                        updated_at DATETIME, restored_at DATETIME
                    )
                """)
                db.execSQL("CREATE TABLE expense_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, expense_id INTEGER NOT NULL, tag_id INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE action_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, action_type TEXT, timestamp DATETIME, original_data TEXT)")
                db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE fixed_costs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, category_id INTEGER DEFAULT 0, is_active INTEGER DEFAULT 1, created_at DATETIME)")
                db.execSQL("CREATE TABLE budget_periods (period_from TEXT PRIMARY KEY, period_to TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, created_at DATETIME)")
                db.execSQL("CREATE TABLE budget_extras (id INTEGER PRIMARY KEY AUTOINCREMENT, period_from TEXT NOT NULL, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, created_at DATETIME)")
                db.execSQL("CREATE TABLE fixed_cost_history (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, category_id INTEGER DEFAULT 0, valid_from TEXT NOT NULL, valid_to TEXT, created_at DATETIME)")
                db.execSQL("CREATE TABLE input_templates (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, name TEXT DEFAULT '', price INTEGER NOT NULL DEFAULT 0, category_id INTEGER DEFAULT 0, tag_ids TEXT DEFAULT '', card_id INTEGER DEFAULT 0, points INTEGER DEFAULT 0, entry_type TEXT NOT NULL DEFAULT 'EXPENSE', sort_order INTEGER DEFAULT 0, created_at DATETIME)")
                db.execSQL("CREATE TABLE category_budgets (period_from TEXT NOT NULL, category_id INTEGER NOT NULL, amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (period_from, category_id))")

                db.insert("categories", null, ContentValues().apply { put("name", "Web制作"); put("cat_type", "EXPENSE") })
                db.insert("expenses", null, ContentValues().apply {
                    put("name", "既存売上")
                    put("price", 120000)
                    put("category_id", 1)
                    put("tag_ids", "")
                    put("date", "2026-03-15")
                    put("points", 0)
                    put("card_id", 0)
                    put("entry_type", "EXPENSE")
                })
                db.insert("input_templates", null, ContentValues().apply {
                    put("title", "定番案件")
                    put("name", "保守費")
                    put("price", 15000)
                    put("category_id", 1)
                    put("entry_type", "EXPENSE")
                })
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        legacyHelper.writableDatabase.close()
        legacyHelper.close()

        val dbh = DatabaseHelper(context, dbName)
        assertEquals(36, dbh.readableDatabase.version)

        val recs = JSONArray(dbh.getRecords(0))
        assertEquals(1, recs.length())
        val r = recs.getJSONObject(0)
        assertEquals("既存売上", r.getString("name"))
        assertEquals(120000, r.getInt("price"))
        assertEquals(0, r.getInt("tax_rate"))
        assertEquals(0, r.getInt("invoice_flag"))
        assertEquals("PAID", r.getString("settlement_status"))
        assertEquals("", r.getString("due_date"))

        val tpls = JSONArray(dbh.getInputTemplates())
        assertEquals(1, tpls.length())
        assertEquals("定番案件", tpls.getJSONObject(0).getString("title"))
        assertEquals(0, tpls.getJSONObject(0).getInt("tax_rate"))
        assertEquals("PAID", tpls.getJSONObject(0).getString("settlement_status"))
        dbh.close()
    }

    @Test
    fun `v36 biz cashflow, calendar month summary, unpaid tracking, and settlement work accurately`() {
        val dbh = newHelper("biz_cashflow")
        val costTagId = dbh.ensureDefaultCostTag()
        assertTrue(costTagId >= 10000)

        // 売上（入金済）: 100,000円 (10%, 適格)
        val paidSaleId = dbh.insertExpense(
            "A社LP制作", 100000, 1, "",
            "2026-04-05", 0, 0, "EXPENSE",
            10, 1, "PAID", ""
        ).toInt()
        assertTrue(paidSaleId > 0)

        // 売上（未入金・期限超過）: 55,000円 (10%, 適格, due 2000-01-01)
        val unpaidSaleId = dbh.insertExpense(
            "B社バナー", 55000, 1, "",
            "2026-04-05", 0, 0, "EXPENSE",
            10, 1, "UNPAID", "2000-01-01"
        ).toInt()

        // 経費（cost_tags ID）: 22,000円 (10%, 適格, 入金済)
        val costId = dbh.insertExpense(
            "Adobe CC", 22000, 1, "",
            "2026-04-05", 0, costTagId, "EXPENSE",
            10, 1, "PAID", ""
        ).toInt()
        assertTrue(costId > 0)

        val cf = JSONObject(dbh.getBizCashflowByRange("2026-04-01", "2026-04-30"))
        assertEquals(155000, cf.getInt("grossSales"))
        assertEquals(22000, cf.getInt("costTotal"))
        assertEquals(133000, cf.getInt("netSales"))
        assertEquals(55000, cf.getInt("unpaidSales"))
        assertEquals(1, cf.getInt("unpaidCount"))

        val cal = JSONArray(dbh.getBizCalendarMonthSummary("2026-04"))
        assertEquals(1, cal.length())
        assertEquals(155000, cal.getJSONObject(0).getInt("sales"))
        assertEquals(22000, cal.getJSONObject(0).getInt("costs"))
        assertEquals(133000, cal.getJSONObject(0).getInt("netSales"))
        assertEquals(1, cal.getJSONObject(0).getInt("unpaidCount"))

        // 未入金サマリー確認
        val unpaidBefore = JSONObject(dbh.getUnpaidSummary())
        assertEquals(1, unpaidBefore.getInt("count"))
        assertEquals(55000, unpaidBefore.getInt("salesAmount"))
        assertEquals(1, unpaidBefore.getInt("overdueCount"))

        // 検索フィルター: bizKind=COST & settlementStatus=UNPAID
        val costRecs = JSONArray(dbh.searchRecords(JSONObject().apply {
            put("periodPreset", "CUSTOM")
            put("dateFrom", "2026-04-01")
            put("dateTo", "2026-04-30")
            put("bizKind", "COST")
        }.toString(), 0))
        assertEquals(1, costRecs.length())
        assertEquals("Adobe CC", costRecs.getJSONObject(0).getString("name"))

        val unpaidRecs = JSONArray(dbh.searchRecords(JSONObject().apply {
            put("periodPreset", "CUSTOM")
            put("dateFrom", "2026-04-01")
            put("dateTo", "2026-04-30")
            put("settlementStatus", "UNPAID")
        }.toString(), 0))
        assertEquals(1, unpaidRecs.length())
        assertEquals("B社バナー", unpaidRecs.getJSONObject(0).getString("name"))

        // 1タップ入金消込
        assertEquals(1, dbh.markAsSettled(unpaidSaleId))
        val unpaidAfter = JSONObject(dbh.getUnpaidSummary())
        assertEquals(0, unpaidAfter.getInt("count"))
        assertEquals(0, JSONObject(dbh.getBizCashflowByRange("2026-04-01", "2026-04-30")).getInt("unpaidCount"))

        // 一括消込 (markManyAsSettled)
        val u1 = dbh.insertExpense("C社", 10000, 1, "", "2026-04-10", 0, 0, "EXPENSE", 10, 0, "UNPAID", "2026-05-10").toInt()
        val u2 = dbh.insertExpense("D社", 20000, 1, "", "2026-04-12", 0, 0, "EXPENSE", 10, 0, "UNPAID", "2026-05-12").toInt()
        assertEquals(2, dbh.markManyAsSettled(intArrayOf(u1, u2)))
        assertEquals(0, JSONObject(dbh.getUnpaidSummary()).getInt("count"))

        dbh.close()
    }

    @Test
    fun `memo suggestions, annual tax report, v36 input templates, and biz csv export work accurately`() {
        val dbh = newHelper("biz_report")
        val costTagId = dbh.insertMaster("cost_tags", "通信費").toInt()
        assertTrue(costTagId >= 10000)

        // 固定費履歴を登録 (2026-01から月10,000円 -> 年間120,000円)
        dbh.addFixedCostHistory("事務所Wifi", 10000, 0, "2026-01-01")

        // 売上・経費データ投入
        dbh.insertExpense("A社コンサル", 200000, 1, "", "2026-02-10", 0, 0, "EXPENSE", 10, 1, "PAID", "")
        dbh.insertExpense("A社コンサル", 150000, 1, "", "2026-03-10", 0, 0, "EXPENSE", 10, 1, "UNPAID", "2026-04-30")
        dbh.insertExpense("物販(軽減)", 8000, 1, "", "2026-03-20", 0, 0, "EXPENSE", 8, 0, "PAID", "")
        dbh.insertExpense("AWS利用料", 12000, 1, "", "2026-03-25", 0, costTagId, "EXPENSE", 10, 1, "PAID", "")
        dbh.insertExpense("簡易レシート", 3000, 1, "", "2026-03-28", 0, costTagId, "EXPENSE", 8, 0, "PAID", "")

        // メモ候補（頻度順）
        val suggestions = JSONArray(dbh.getMemoSuggestions("コンサル", 8))
        assertEquals(1, suggestions.length())
        assertEquals("A社コンサル", suggestions.getJSONObject(0).getString("name"))
        assertEquals(2, suggestions.getJSONObject(0).getInt("count"))

        // v36 テンプレート保存・取得・JSONバックアップ往復
        dbh.saveInputTemplate(JSONObject().apply {
            put("title", "月次保守請求")
            put("name", "A社保守")
            put("price", 33000)
            put("category_id", 1)
            put("card_id", 0)
            put("entry_type", "EXPENSE")
            put("tax_rate", 10)
            put("invoice_flag", 1)
            put("settlement_status", "UNPAID")
            put("due_date", "2026-05-31")
        }.toString())
        val tpls = JSONArray(dbh.getInputTemplates())
        assertEquals(1, tpls.length())
        assertEquals(10, tpls.getJSONObject(0).getInt("tax_rate"))
        assertEquals(1, tpls.getJSONObject(0).getInt("invoice_flag"))
        assertEquals("UNPAID", tpls.getJSONObject(0).getString("settlement_status"))
        assertEquals("2026-05-31", tpls.getJSONObject(0).getString("due_date"))

        // 年次確定申告レポート
        val report = JSONObject(dbh.getAnnualTaxReport(2026))
        assertEquals(2026, report.getInt("year"))
        assertEquals(358000, report.getInt("grossSales"))
        assertEquals(208000, report.getInt("paidSales"))
        assertEquals(150000, report.getInt("unpaidSales"))
        assertEquals(15000, report.getInt("costTotal"))
        assertEquals(120000, report.getInt("fixedTotal"))
        assertEquals(343000, report.getInt("netIncomeBeforeFixed"))
        assertEquals(223000, report.getInt("netIncome"))

        val tax = report.getJSONObject("taxSummary")
        assertEquals(350000, tax.getInt("sales10"))
        assertEquals(8000, tax.getInt("sales8"))
        assertEquals(12000, tax.getInt("cost10"))
        assertEquals(3000, tax.getInt("cost8"))
        assertEquals(12000, tax.getInt("invoiceCostTotal"))
        assertEquals(3000, tax.getInt("nonInvoiceCostTotal"))

        val breakdown = report.getJSONArray("costBreakdown")
        assertEquals(1, breakdown.length())
        assertEquals("通信費", breakdown.getJSONObject(0).getString("tagName"))
        assertEquals(15000, breakdown.getJSONObject(0).getInt("amount"))

        // 売上管理モード CSV エクスポート（数式防御含む）
        dbh.insertExpense("=DANGER()", 1000, 1, "", "2026-03-29", 0, costTagId, "EXPENSE", 10, 1, "PAID", "")
        val bizCsv = dbh.buildCsvString(JSONObject().apply {
            put("periodPreset", "CUSTOM")
            put("dateFrom", "2026-01-01")
            put("dateTo", "2026-12-31")
            put("bizMode", true)
        }.toString())
        assertTrue(bizCsv.startsWith("日付,種別,カテゴリ,金額,税区分,適格請求書,入金状態,入金予定日,タグ,メモ,経費タグ,登録日時\r\n"))
        assertTrue(bizCsv.contains("売上,") && bizCsv.contains("10%,適格,未入金,2026-04-30"))
        assertTrue(bizCsv.contains("経費,") && bizCsv.contains("通信費"))
        assertTrue(bizCsv.contains("'=DANGER()"))

        dbh.close()
    }
}
