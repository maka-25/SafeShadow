package com.example.safeshadow.ui

import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.safeshadow.Contact
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R

class SetupActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var spinnerCountry: Spinner
    private lateinit var btnAdd: Button
    private lateinit var btnDone: Button
    private lateinit var rvContacts: RecyclerView
    private lateinit var tvCount: TextView

    private val contacts = mutableListOf<Contact>()
    private lateinit var adapter: ContactAdapter

    private val MAX_CONTACTS = 5

    data class Country(val name: String, val code: String, val length: Int)

    private val countries = listOf(
        Country("India (+91)", "+91", 10),
        Country("USA (+1)", "+1", 10),
        Country("UK (+44)", "+44", 10),
        Country("Australia (+61)", "+61", 9)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        etName     = findViewById(R.id.etContactName)
        etPhone    = findViewById(R.id.etContactPhone)
        spinnerCountry = findViewById(R.id.spinnerCountry)
        btnAdd     = findViewById(R.id.btnAddContact)
        btnDone    = findViewById(R.id.btnDone)
        rvContacts = findViewById(R.id.rvContacts)
        tvCount    = findViewById(R.id.tvContactCount)

        setupCountrySpinner()

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

    private fun setupCountrySpinner() {
        val adapterSpinner = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            countries.map { it.name }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val textView = super.getView(position, convertView, parent) as TextView
                textView.text = countries[position].code   // show only +91
                textView.setTextColor(android.graphics.Color.WHITE)
                return textView
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val textView = super.getDropDownView(position, convertView, parent) as TextView
                textView.text = countries[position].name   // show India (+91)
                textView.setTextColor(android.graphics.Color.WHITE)
                textView.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                return textView
            }
        }
        spinnerCountry.adapter = adapterSpinner

        spinnerCountry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = countries[position]

                etPhone.filters = arrayOf(
                    InputFilter.LengthFilter(selected.length),
                    InputFilter { source, _, _, _, _, _ ->
                        if (source.all { it.isDigit() }) source else ""
                    }
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun addContact() {
        val name  = etName.text.toString().trim()
        val phone = etPhone.text.toString().trim()
        val selectedCountry = countries[spinnerCountry.selectedItemPosition]

        val digitsOnly = phone.filter { it.isDigit() }

        when {
            name.isEmpty() -> {
                etName.error = "Enter a name"
                return
            }

            !name.any { it.isLetter() } -> {
                etName.error = "Name must contain at least one letter"
                return
            }

            phone.isEmpty() -> {
                etPhone.error = "Enter a phone number"
                return
            }

            digitsOnly.length != selectedCountry.length -> {
                etPhone.error = "Enter valid ${selectedCountry.length}-digit number"
                return
            }

            contacts.size >= MAX_CONTACTS -> {
                Toast.makeText(
                    this,
                    "Maximum $MAX_CONTACTS contacts allowed",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            contacts.any { it.phone == selectedCountry.code + digitsOnly } -> {
                etPhone.error = "This number is already added"
                return
            }

            contacts.any { it.name.equals(name, ignoreCase = true) } -> {
                etName.error = "A contact with this name already exists"
                return
            }
        }

        val fullNumber = selectedCountry.code + digitsOnly

        val contact = Contact(name, fullNumber)
        contacts.add(contact)
        adapter.notifyItemInserted(contacts.size - 1)

        etName.text.clear()
        etPhone.text.clear()

        updateCount()
        Toast.makeText(this, "$name added", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmation(position: Int) {
        if (position < 0 || position >= contacts.size) return

        val contact = contacts[position]

        AlertDialog.Builder(this)
            .setTitle("Delete Contact")
            .setMessage("Remove ${contact.name} (${contact.phone})?")
            .setPositiveButton("Delete") { _, _ ->
                contacts.removeAt(position)
                adapter.notifyItemRemoved(position)
                updateCount()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCount() {
        tvCount.text = "${contacts.size} / $MAX_CONTACTS contacts added"
    }

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
            holder.name.text = contact.name
            holder.phone.text = contact.phone
            holder.icon.text = contact.name.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
            holder.delete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}