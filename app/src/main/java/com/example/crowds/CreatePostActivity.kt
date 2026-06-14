package com.example.crowds

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.crowds.databinding.ActivityCreatePostBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Date

class CreatePostActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_LOCATION_PERM = 2
    }

    private lateinit var binding: ActivityCreatePostBinding
    private lateinit var mapView: MapView
    private var selectedLocation: GeoPoint? = null

    private val firestore = FirebaseFirestore.getInstance()
    private val postsCollection = firestore.collection("posts")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreatePostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )

        mapView = binding.mapPickerView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(55.7558, 37.6173))

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERM
            )
        } else {
            enableMyLocationOverlay()
        }

        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint): Boolean {
                setSelectedPoint(p)
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(eventsReceiver))

        setupCategorySpinner()
        binding.btnBackCreate.setOnClickListener { finish() }
        binding.btnSubmit.setOnClickListener { createPost() }
    }

    private fun setupCategorySpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            PostCategory.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapter
        binding.spinnerCategory.setSelection(PostCategory.OTHER.ordinal)
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationOverlay() {
        val provider = GpsMyLocationProvider(this)
        val myLocationOverlay = MyLocationNewOverlay(provider, mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                myLocationOverlay.myLocation?.let { location ->
                    mapView.controller.animateTo(location)
                    mapView.controller.setZoom(16.0)
                }
            }
        }
    }

    private fun setSelectedPoint(p: GeoPoint) {
        mapView.overlays
            .filter { it is Marker && it.title == "Выбор" }
            .forEach { mapView.overlays.remove(it) }

        val marker = Marker(mapView).apply {
            position = p
            title = "Выбор"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@CreatePostActivity, android.R.drawable.star_on)
        }
        mapView.overlays.add(marker)
        mapView.invalidate()

        selectedLocation = p
        binding.tvSelectedCoords.text =
            "Выбрано: ${"%.5f".format(p.latitude)}, ${"%.5f".format(p.longitude)}"
    }

    private fun createPost() {
        val titleText = binding.etTitle.text.toString().trim()
        val descText = binding.etDesc.text.toString().trim()
        val category = PostCategory.fromPosition(binding.spinnerCategory.selectedItemPosition)

        if (titleText.isEmpty()) {
            binding.tilTitle.error = "Введите заголовок"
            return
        } else {
            binding.tilTitle.error = null
        }
        if (descText.isEmpty()) {
            binding.tilDesc.error = "Введите описание"
            return
        } else {
            binding.tilDesc.error = null
        }
        if (selectedLocation == null) {
            Toast.makeText(this, "Выберите точку на карте долгим нажатием", Toast.LENGTH_SHORT).show()
            return
        }

        checkDuplicatesAndCreatePost(titleText, descText, category)
    }

    private fun checkDuplicatesAndCreatePost(
        titleText: String,
        descText: String,
        category: PostCategory
    ) {
        val threeHoursAgo = Timestamp(Date(System.currentTimeMillis() - 3 * 60 * 60 * 1000L))
        postsCollection
            .whereGreaterThanOrEqualTo("timestamp", threeHoursAgo)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val selected = selectedLocation ?: return@addOnSuccessListener
                val recentPosts = snap.documents.mapNotNull { doc ->
                    doc.toObject(Post::class.java)?.apply { id = doc.id }
                }
                val duplicateCandidateIds = DuplicateDetector.findDuplicateCandidateIds(
                    selected.latitude,
                    selected.longitude,
                    recentPosts
                )

                if (duplicateCandidateIds.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Возможный дубль")
                        .setMessage("Поблизости уже есть похожее событие. Возможно, это дубль. Всё равно создать новое сообщение?")
                        .setPositiveButton("Создать") { _, _ ->
                            savePost(titleText, descText, category, duplicateCandidateIds)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    savePost(titleText, descText, category, duplicateCandidateIds)
                }
            }
            .addOnFailureListener {
                savePost(titleText, descText, category, emptyList())
            }
    }

    private fun savePost(
        titleText: String,
        descText: String,
        category: PostCategory,
        duplicateCandidateIds: List<String>
    ) {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val authorUid = firebaseUser?.uid.orEmpty()
        val authorEmail = firebaseUser?.email.orEmpty()
        val userName = firebaseUser?.displayName ?: "Anonymous"

        if (authorUid.isBlank()) {
            Toast.makeText(this, "Не удалось определить пользователя", Toast.LENGTH_SHORT).show()
            return
        }

        val userRef = firestore.collection("users").document(authorUid)
        userRef.get()
            .addOnSuccessListener { userSnap ->
                val reputation = userSnap.toObject(UserReputation::class.java)
                    ?: UserReputation(uid = authorUid, email = authorEmail, displayName = userName)
                val now = Timestamp.now()
                val trust = TrustScoreCalculator.calculateTrustScore(
                    status = PostStatus.PENDING,
                    confirmationsCount = 0,
                    reportsCount = 0,
                    duplicateCount = duplicateCandidateIds.size,
                    authorReputation = reputation.reputationScore,
                    createdAt = now.toDate().time
                )
                val selected = selectedLocation ?: return@addOnSuccessListener

                val newPost = Post().apply {
                    title = titleText
                    description = descText
                    this.category = category.name
                    latitude = selected.latitude
                    longitude = selected.longitude
                    this.authorUid = authorUid
                    this.authorEmail = authorEmail
                    this.userName = userName
                    timestamp = now
                    confirmationsCount = 0
                    reportsCount = 0
                    duplicateCount = duplicateCandidateIds.size
                    trustScore = trust.score
                    trustLevel = trust.level
                    this.duplicateCandidateIds = duplicateCandidateIds
                    needsAdminReview = duplicateCandidateIds.isNotEmpty() || trust.score < 30.0
                    status = PostStatus.PENDING
                }

                postsCollection.add(newPost)
                    .addOnSuccessListener {
                        userRef.set(
                            mapOf(
                                "uid" to authorUid,
                                "email" to authorEmail,
                                "displayName" to userName,
                                "reputationScore" to reputation.reputationScore,
                                "postsCreated" to FieldValue.increment(1),
                                "confirmationsMade" to reputation.confirmationsMade,
                                "reportsMade" to reputation.reportsMade
                            ),
                            SetOptions.merge()
                        )
                        finish()
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        Toast.makeText(this, "Ошибка при создании поста", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Не удалось загрузить репутацию пользователя", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_LOCATION_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocationOverlay()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
