package fr.hodr.aip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import pia.core.HttpPort
import pia.core.ServerCatalog
import pia.core.ServerEntry

/**
 * Full-screen server list with a search filter. The [EditText] filters by
 * display title or id (case-insensitive). Each row shows the badge code and
 * human-readable title with text tags for virtual or streaming-optimized
 * regions; hostnames and IPs never appear. Tapping a row returns the full
 * entry to [MainActivity] via setResult.
 */
class ServerListActivity : Activity() {

    private val catalog = ServerCatalog(HttpUrlConnectionPortImpl)
    private var servers: List<ServerEntry> = emptyList()
    private var filtered: List<ServerEntry> = emptyList()

    private lateinit var searchField: EditText
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_list)

        searchField = findViewById(R.id.serverSearch)
        listView = findViewById(R.id.serverList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = filtered.getOrNull(position) ?: return@setOnItemClickListener
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra(EXTRA_SERVER_ID, entry.id)
                    putExtra(EXTRA_SERVER_HOSTNAME, entry.hostname)
                    putExtra(EXTRA_SERVER_TITLE, entry.displayTitle())
                    putExtra(EXTRA_SERVER_VIRTUAL, entry.virtual)
                    putExtra(EXTRA_SERVER_STREAMING, entry.streaming)
                },
            )
            finish()
        }

        loadServers()
    }

    private fun loadServers() {
        adapter.clear()
        adapter.add("Loading servers...")
        Thread {
            try {
                val fetched = catalog.fetchServers()
                servers = fetched
                runOnUiThread { applyFilter(searchField.text.toString()) }
            } catch (error: Exception) {
                // Blocked network (kill switch without a tunnel): fall back to
                // the cached list so a server can still be picked. The cached
                // lease then dials by literal IP, no DNS needed.
                val cached = try {
                    OfflineCache(this@ServerListActivity).loadServerList()
                } catch (ignored: Exception) {
                    null
                }
                val cachedServers = try {
                    if (cached != null) catalog.parseBody(cached.first) else null
                } catch (ignored: Exception) {
                    null
                }
                runOnUiThread {
                    if (!cachedServers.isNullOrEmpty()) {
                        servers = cachedServers
                        val ageHours =
                            (System.currentTimeMillis() - cached!!.second) / 3_600_000
                        filtered = cachedServers
                        adapter.clear()
                        adapter.addAll(cachedServers.map { ServerLabels.label(it) })
                        adapter.notifyDataSetChanged()
                        Toast.makeText(
                            this@ServerListActivity,
                            "Showing cached servers (${ageHours}h old)",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        adapter.clear()
                        adapter.add(
                            "Server list failed: " +
                                "${error.message ?: error.javaClass.simpleName}"
                        )
                    }
                }
            }
        }.start()
    }

    private fun applyFilter(query: String) {
        val needle = query.trim().lowercase()
        filtered = if (needle.isEmpty()) {
            servers
        } else {
            servers.filter {
                it.displayTitle().lowercase().contains(needle) || it.id.lowercase().contains(needle)
            }
        }
        adapter.clear()
        if (filtered.isEmpty() && servers.isNotEmpty()) {
            adapter.add("No servers match \"$query\"")
        } else {
            adapter.addAll(filtered.map { ServerLabels.label(it) })
        }
        adapter.notifyDataSetChanged()
    }

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SERVER_HOSTNAME = "serverHostname"
        const val EXTRA_SERVER_TITLE = "serverTitle"
        const val EXTRA_SERVER_VIRTUAL = "serverVirtual"
        const val EXTRA_SERVER_STREAMING = "serverStreaming"

        private val HttpUrlConnectionPortImpl: HttpPort = HttpUrlConnectionPort()
    }
}
