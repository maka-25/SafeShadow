package com.example.safeshadow.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.safeshadow.Contact
import com.example.safeshadow.PrefsHelper
import com.safeshadow.R

class SetupActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var btnAdd: Button
    private lateinit var btnDone: Button
    private lateinit var rvContacts: RecyclerView
    private lateinit var tvCount: TextView

    private val contacts = mutableListOf<Contact>()
    private lateinit var adapter: ContactAdapter

    private val MAX_CONTACTS = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        etName     = findViewById(R.id.etContactName)
        etPhone    = findViewById(R.id.etContactPhone)
        btnAdd     = findViewById(R.id.btnAddContact)
        btnDone    = findViewById(R.id.btnDone)
        rvContacts = findViewById(R.id.rvContacts)
        tvCount    = findViewById(R.id.tvContactCount)

        // Load saved contacts
        contacts.addAll(PrefsHelper.getContacts(this))

        // Setup RecyclerView
        adapter = ContactAdapter(contacts) { position ->
            contacts.removeAt(position)
            adapter.notifyItemRemoved(position)
            updateCount()
        }
        rvContacts.layoutManager = LinearLayoutManager(this)
        rvContacts.adapter = adapter

        updateCount()

        btnAdd.setOnClickListener {
            addContact()
        }

        btnDone.setOnClickListener {
            PrefsHelper.saveContacts(this, contacts)
            Toast.makeText(this, "Contacts saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun addContact() {
        val name  = etName.text.toString().trim()
        val phone = etPhone.text.toString().trim()

        // Validation
        when {
            name.isEmpty() -> {
                etName.error = "Enter a name"
                return
            }
            phone.isEmpty() -> {
                etPhone.error = "Enter a phone number"
                return
            }
            phone.length < 10 -> {
                etPhone.error = "Enter a valid number"
                return
            }
            contacts.size >= MAX_CONTACTS -> {
                Toast.makeText(this,
                    "Maximum $MAX_CONTACTS contacts allowed",
                    Toast.LENGTH_SHORT).show()
                return
            }
            contacts.any { it.phone == phone } -> {
                etPhone.error = "This number is already added"
                return
            }
        }

        val contact = Contact(name, phone)
        contacts.add(contact)
        adapter.notifyItemInserted(contacts.size - 1)

        // Clear inputs
        etName.text.clear()
        etPhone.text.clear()

        updateCount()
        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
    }

    private fun updateCount() {
        tvCount.text = "${contacts.size} / $MAX_CONTACTS contacts added"
    }

    // ─── Adapter ───────────────────────────────────────────────────────────────

    inner class ContactAdapter(
        private val items: MutableList<Contact>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon:   TextView = view.findViewById(R.id.tvContactIcon)
            val name:   TextView = view.findViewById(R.id.tvContactName)
            val phone:  TextView = view.findViewById(R.id.tvContactPhone)
            val delete: Button   = view.findViewById(R.id.btnDeleteContact)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = items[position]
            holder.name.text  = contact.name
            holder.phone.text = contact.phone
            // First letter of name as icon
            holder.icon.text  = contact.name.first().uppercase()
            holder.delete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}