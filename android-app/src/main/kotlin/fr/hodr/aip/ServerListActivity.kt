package fr.hodr.aip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import pia.core.HttpPort
import pia.core.ServerCatalog
import pia.core.ServerEntry

/**
 * Full-screen server list with a search filter. The [EditText] filters by
 * id or hostname (case-insensitive). Each row shows a flag emoji derived
 * from the [ServerEntry] id prefix plus id and hostname. Tapping a row
 * returns the chosen id/hostname to [MainActivity] via setResult.
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
                runOnUiThread {
                    adapter.clear()
                    adapter.add("Server list failed: ${error.message ?: error.javaClass.simpleName}")
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
                it.id.lowercase().contains(needle) || it.hostname.lowercase().contains(needle)
            }
        }
        adapter.clear()
        if (filtered.isEmpty() && servers.isNotEmpty()) {
            adapter.add("No servers match \"$query\"")
        } else {
            adapter.addAll(filtered.map { "${flagFor(it.id)} ${it.id} (${it.hostname})" })
        }
        adapter.notifyDataSetChanged()
    }

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SERVER_HOSTNAME = "serverHostname"

        private val HttpUrlConnectionPortImpl: HttpPort = HttpUrlConnectionPort()

        /**
         * Flag emoji for a region id: the first two ASCII letters uppercased
         * map to regional indicator symbols; anything else gets the globe.
         */
        fun flagFor(id: String): String {
            if (id.length < 2) {
                return "\uD83C\uDF10"
            }
            val first = id[0].uppercaseChar()
            val second = id[1].uppercaseChar()
            if (first !in 'A'..'Z' || second !in 'A'..'Z') {
                return "\uD83C\uDF10"
            }
            val base = 0x1F1E6
            val firstPoint = base + (first - 'A')
            val secondPoint = base + (second - 'A')
            return String(Character.toChars(firstPoint)) + String(Character.toChars(secondPoint))
        }
    }
}
