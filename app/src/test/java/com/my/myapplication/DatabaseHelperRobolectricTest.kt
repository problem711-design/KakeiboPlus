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

        // v35 DatabaseHelper で開いてマイグレーション実行
        val dbh = DatabaseHelper(context, dbName)
        assertEquals(35, dbh.readableDatabase.version)
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

        val exportedV35 = dbh.exportAll("家")
        val exportedObj = JSONObject(exportedV35)
        assertEquals(35, exportedObj.getInt("version"))

        val dbh2 = newHelper("roundtrip")
        assertEquals("", dbh2.importAll(exportedV35, "家"))
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
}
