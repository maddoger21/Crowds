package com.example.crowds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.crowds.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 1
        private const val RADIUS_KM = 5.0
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var userLocation: GeoPoint? = null

    private val firestore = FirebaseFirestore.getInstance()
    private val postsCollection = firestore.collection("posts")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Настраиваем Toolbar как ActionBar
        setSupportActionBar(binding.toolbarMain)

        // После входа через Google показываем имя пользователя в Toolbar (subtitle)
        val currentUser = FirebaseAuth.getInstance().currentUser
        val displayName = currentUser?.displayName ?: "Anonymous"
        supportActionBar?.subtitle = "Вы: $displayName"

        // Инициализируем OsmDroid
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )

        // Настраиваем MapView
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(14.0)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Запрашиваем разрешения
        requestPermissionsIfNecessary()

        // Обработчик клика на FAB → открываем экран создания поста
        binding.fabCreatePost.setOnClickListener {
            // Непосредственно Intent для CreatePostActivity
            startActivity(Intent(this, CreatePostActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        getLastLocationAndLoadPosts()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun requestPermissionsIfNecessary() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocationAndLoadPosts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                userLocation = GeoPoint(location.latitude, location.longitude)
                binding.mapView.controller.setCenter(userLocation)

                // Маркер "Вы здесь"
                val userMarker = Marker(binding.mapView).apply {
                    position = userLocation
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
                    title = "Вы здесь"
                }
                binding.mapView.overlays.add(userMarker)
                loadPostsFromFirestore()
            } else {
                requestNewLocationData()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = Priority.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            userLocation = GeoPoint(location.latitude, location.longitude)
            binding.mapView.controller.setCenter(userLocation)
            loadPostsFromFirestore()
        }
    }

    private fun loadPostsFromFirestore() {
        // Удаляем все маркеры, кроме "Вы здесь"
        binding.mapView.overlays.removeIf { it is Marker && (it as Marker).title != "Вы здесь" }

        postsCollection.orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val posts = mutableListOf<Post>()
                for (doc in querySnapshot.documents) {
                    val post = doc.toObject(Post::class.java)
                    post?.let {
                        it.id = doc.id
                        posts.add(it)
                    }
                }
                // Фильтруем по радиусу
                val toShow = posts.filter { post ->
                    userLocation?.let { user ->
                        val dist = Utils.distanceKm(
                            user.latitude, user.longitude,
                            post.latitude, post.longitude
                        )
                        dist <= RADIUS_KM
                    } ?: false
                }
                // Добавляем маркеры для каждого поста
                for (post in toShow) {
                    addPostMarker(post)
                }
                binding.mapView.invalidate()
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
            }
    }

    private fun addPostMarker(post: Post) {
        val marker = Marker(binding.mapView).apply {
            position = GeoPoint(post.latitude, post.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = post.title
            snippet = post.description
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_dialog_alert)
        }
        marker.setOnMarkerClickListener { _, _ ->
            showPostDetailsDialog(post)
            true
        }
        binding.mapView.overlays.add(marker)
    }

    private fun showPostDetailsDialog(post: Post) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_post_details, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_post_title)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tv_post_desc)
        val rvComments = dialogView.findViewById<RecyclerView>(R.id.rv_comments)
        val etComment = dialogView.findViewById<EditText>(R.id.et_comment)
        val btnSend = dialogView.findViewById<Button>(R.id.btn_send_comment)

        tvTitle.text = post.title
        tvDesc.text = post.description

        rvComments.layoutManager = LinearLayoutManager(this)
        val commentsList = mutableListOf<Comment>()
        val adapter = CommentAdapter(commentsList)
        rvComments.adapter = adapter

        firestore.collection("posts")
            .document(post.id)
            .collection("comments")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                commentsList.clear()
                for (doc in snapshot.documents) {
                    val comment = doc.toObject(Comment::class.java)
                    comment?.let {
                        it.id = doc.id
                        commentsList.add(it)
                    }
                }
                adapter.notifyDataSetChanged()
                if (commentsList.isNotEmpty()) {
                    rvComments.scrollToPosition(commentsList.size - 1)
                }
            }

        btnSend.setOnClickListener {
            val text = etComment.text.toString().trim()
            if (text.isNotEmpty()) {
                val firebaseUser = FirebaseAuth.getInstance().currentUser
                val userName = firebaseUser?.displayName ?: "Anonymous"
                val newComment = Comment(
                    userName = userName,
                    text = text,
                    timestamp = Timestamp.now()
                )
                firestore.collection("posts")
                    .document(post.id)
                    .collection("comments")
                    .add(newComment)
                    .addOnSuccessListener {
                        etComment.text?.clear()
                        commentsList.add(newComment)
                        adapter.notifyItemInserted(commentsList.size - 1)
                        rvComments.scrollToPosition(commentsList.size - 1)
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                    }
            }
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Закрыть", null)
            .create()
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLastLocationAndLoadPosts()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------- Поведение меню в Toolbar -------------------

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                // Показать подтверждение выхода
                showLogoutConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Выход из аккаунта")
            .setMessage("Вы действительно хотите выйти?")
            .setPositiveButton("Да") { dialog, _ ->
                // Выполняем выход и переходим на экран входа
                FirebaseAuth.getInstance().signOut()
                val intent = Intent(this, SignInActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Нет") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}
