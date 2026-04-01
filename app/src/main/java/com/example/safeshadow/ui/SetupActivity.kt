package com.example.safeshadow.ui

import android.os.Bundle
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.safeshadow.Contact
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R

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

        // Cap phone field at 13 characters while typing
        etPhone.filters = arrayOf(InputFilter.LengthFilter(13))

        // Load saved contacts
        contacts.addAll(PrefsHelper.getContacts(this))

        adapter = ContactAdapter(contacts) { position ->
            showDeleteConfirmation(position)
        }
        rvContacts.layoutManager = LinearLayoutManager(this)
        rvContacts.adapter = adapter

        updateCount()

        btnAdd.setOnClickListener { addContact() }

        btnDone.setOnClickListener {
            PrefsHelper.saveContacts(this, contacts)
            Toast.makeText(this, "Contacts saved!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun addContact() {
        val name  = etName.text.toString().trim()
        val phone = etPhone.text.toString().trim()

        // Strip + and spaces to count actual digits
        val digitsOnly = phone.replace("+", "").replace(" ", "")

        when {
            // Name empty check
            name.isEmpty() -> {
                etName.error = "Enter a name"
                return
            }
            // Name must have at least one letter — blocks pure numbers
            !name.any { it.isLetter() } -> {
                etName.error = "Name must contain at least one letter"
                return
            }
            // Phone empty check
            phone.isEmpty() -> {
                etPhone.error = "Enter a phone number"
                return
            }
            // Minimum 10 digits
            digitsOnly.length < 10 -> {
                etPhone.error = "Number too short — minimum 10 digits"
                return
            }
            // Maximum 12 digits (91XXXXXXXXXX)
            digitsOnly.length > 12 -> {
                etPhone.error = "Number too long — maximum 12 digits"
                return
            }
            // Max contacts reached
            contacts.size >= MAX_CONTACTS -> {
                Toast.makeText(this,
                    "Maximum $MAX_CONTACTS contacts allowed",
                    Toast.LENGTH_SHORT).show()
                return
            }
            // Duplicate phone check
            contacts.any { it.phone == phone } -> {
                etPhone.error = "This number is already added"
                return
            }
            // Duplicate name check — case insensitive
            contacts.any { it.name.equals(name, ignoreCase = true) } -> {
                etName.error = "A contact with this name already exists"
                return
            }
        }

        val contact = Contact(name, phone)
        contacts.add(contact)
        adapter.notifyItemInserted(contacts.size - 1)

        etName.text.clear()
        etPhone.text.clear()

        updateCount()
        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmation(position: Int) {
        // Guard against invalid position
        if (position < 0 || position >= contacts.size) return

        val contact = contacts[position]
        AlertDialog.Builder(this)
            .setTitle("Delete Contact")
            .setMessage(
                "Remove ${contact.name} (${contact.phone})\n" +
                        "from emergency contacts?"
            )
            .setPositiveButton("Delete") { _, _ ->
                contacts.removeAt(position)
                adapter.notifyItemRemoved(position)
                updateCount()
                Toast.makeText(
                    this,
                    "${contact.name} removed",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            // Use first letter that is a letter — safe for names with numbers
            holder.icon.text  = contact.name.first { it.isLetter() }.uppercase()
            holder.delete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}