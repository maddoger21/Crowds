package com.example.crowds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import android.graphics.Color
import org.osmdroid.views.overlay.Polygon

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var isAdminUser = false

    private var radiusCircle: org.osmdroid.views.overlay.Polygon? = null

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarMain)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
            return
        }

        val email = currentUser.email ?: ""
        firestore.collection("adminsByEmail").document(email).get()
            .addOnSuccessListener { doc ->
                isAdminUser = doc.exists()
                Log.d(TAG, "isAdminUser=$isAdminUser for $email")

                // --- Синхронизируем профиль в /users/{uid} ---
                FirebaseAuth.getInstance().currentUser?.let { user ->
                    syncUserProfile(user.uid, user.displayName ?: "Anonymous")
                }
                // ----------------------------------------------

                runOnUiThread {
                    supportActionBar?.subtitle = if (isAdminUser) "Вы (admin): $email" else "Вы: $email"
                    Toast.makeText(
                        this,
                        if (isAdminUser) "Вы вошли как администратор" else "Вы вошли как пользователь",
                        Toast.LENGTH_LONG
                    ).show()
                }
                getLastLocationAndLoadPosts()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Не удалось проверить права", Toast.LENGTH_LONG).show()
            }

        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(14.0)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestPermissionsIfNecessary()

        binding.fabCreatePost.setOnClickListener {
            startActivity(Intent(this, CreatePostActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun requestPermissionsIfNecessary() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                perms.toTypedArray(),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private var postsListener: ListenerRegistration? = null
    @SuppressLint("MissingPermission")
    private fun getLastLocationAndLoadPosts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                userLocation = GeoPoint(loc.latitude, loc.longitude)
                binding.mapView.controller.setCenter(userLocation)
                addUserMarker()
                drawUserRadius()
                loadPostsFromFirestore()
            } else {
                requestNewLocationData()
            }
        }

        postsListener = postsCollection
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
                // достаём все посты, фильтруем по статусу/радиусу как раньше
                val allPosts = snap.documents.mapNotNull { it.toObject(Post::class.java)?.apply { id = it.id } }
                val toShow = if (isAdminUser) {
                    allPosts
                } else {
                    allPosts.filter {
                        it.status == PostStatus.APPROVED &&
                                userLocation?.let { u ->
                                    Utils.distanceKm(u.latitude, u.longitude, it.latitude, it.longitude) <= RADIUS_KM
                                } ?: false
                    }
                }
                // перерисовываем все маркеры
                binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }
                toShow.forEach { addPostMarker(it) }
                binding.mapView.invalidate()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        postsListener?.remove()
    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val req = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = Priority.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback, null)
    }
    private fun syncUserProfile(uid: String, displayName: String) {
        val userDoc = firestore.collection("users").document(uid)
        userDoc.get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                val role = if (isAdminUser) UserRole.ADMIN else UserRole.USER
                userDoc.set(AppUser(uid, displayName, role))
            } else {
                userDoc.update("role", if (isAdminUser) "ADMIN" else "USER")
            }
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            userLocation = GeoPoint(loc.latitude, loc.longitude)
            binding.mapView.controller.setCenter(userLocation)
            drawUserRadius()
            loadPostsFromFirestore()
        }
    }

    private fun addUserMarker() {
        userLocation?.let {
            val m = Marker(binding.mapView).apply {
                position = it
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(
                    this@MainActivity,
                    android.R.drawable.ic_menu_mylocation
                )
                title = "Вы здесь"
            }
            binding.mapView.overlays.add(m)
        }
    }

    private fun loadPostsFromFirestore() {
        if (isAdminUser) {
            postsCollection.orderBy("timestamp", Query.Direction.DESCENDING)
                .get().addOnSuccessListener { snap ->
                    binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }
                    snap.documents.mapNotNull { doc ->
                        doc.toObject(Post::class.java)?.apply { id = doc.id }
                    }.forEach { addPostMarker(it) }
                    binding.mapView.invalidate()
                }
        } else {
            postsCollection.whereEqualTo("status", PostStatus.APPROVED.name)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get().addOnSuccessListener { snap ->
                    binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }
                    snap.documents.mapNotNull { doc ->
                        doc.toObject(Post::class.java)?.apply { id = doc.id }
                    }.filter { post ->
                        userLocation?.let { u ->
                            Utils.distanceKm(u.latitude, u.longitude, post.latitude, post.longitude) <= RADIUS_KM
                        } ?: false
                    }.forEach { addPostMarker(it) }
                    binding.mapView.invalidate()
                }
        }
    }

    private fun addPostMarker(post: Post) {
        Marker(binding.mapView).apply {
            position = GeoPoint(post.latitude, post.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = post.title
            snippet = post.description
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_dialog_alert)
            setOnMarkerClickListener { _, _ ->
                showPostDetailsDialog(post)
                true
            }
        }.also { binding.mapView.overlays.add(it) }
    }

    private fun showPostDetailsDialog(post: Post) {
        val view = layoutInflater.inflate(R.layout.dialog_post_details, null)
        view.findViewById<TextView>(R.id.tv_post_title).text = post.title
        view.findViewById<TextView>(R.id.tv_post_desc).text  = post.description

        val rvComments = view.findViewById<RecyclerView>(R.id.rv_comments)
        rvComments.layoutManager = LinearLayoutManager(this)

        // 1) Список комментариев
        val comments = mutableListOf<Comment>()

        // 2) Объявляем адаптер заранее, но инициализируем чуть ниже
        lateinit var commentAdapter: CommentAdapter

        // 2) Адаптер объявляем заранее
        commentAdapter = CommentAdapter(comments, isAdminUser) { commentToDelete ->
            // Защита от пустого id
            if (commentToDelete.id.isBlank()) {
                Toast.makeText(this, "Нельзя удалить несохранённый комментарий", Toast.LENGTH_SHORT).show()
                return@CommentAdapter
            }
            firestore.collection("posts")
                .document(post.id)
                .collection("comments")
                .document(commentToDelete.id)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Комментарий удалён", Toast.LENGTH_SHORT).show()
                    val idx = comments.indexOfFirst { it.id == commentToDelete.id }
                    if (idx != -1) {
                        comments.removeAt(idx)
                        commentAdapter.notifyItemRemoved(idx)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Ошибка удаления", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Delete comment failed", e)
                }
        }
        rvComments.adapter = commentAdapter

        // 3) Подгружаем существующие комментарии
        firestore.collection("posts")
            .document(post.id)
            .collection("comments")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snap ->
                comments.clear()
                snap.documents.forEach { d ->
                    d.toObject(Comment::class.java)
                        ?.apply { id = d.id }
                        ?.let { comments.add(it) }
                }
                commentAdapter.notifyDataSetChanged()
                if (comments.isNotEmpty()) rvComments.scrollToPosition(comments.size - 1)
            }

        // 4) Отправка нового комментария
        view.findViewById<Button>(R.id.btn_send_comment).setOnClickListener {
            val et = view.findViewById<EditText>(R.id.et_comment)
            val text = et.text.toString().trim()
            if (text.isNotEmpty()) {
                val name = FirebaseAuth.getInstance().currentUser?.displayName ?: "Anonymous"
                val newComment = Comment(userName = name, text = text, timestamp = Timestamp.now())
                firestore.collection("posts")
                    .document(post.id)
                    .collection("comments")
                    .add(newComment)
                    .addOnSuccessListener { docRef ->
                        // сохраняем сгенерированный Firestore ID
                        newComment.id = docRef.id
                        et.text?.clear()
                        comments.add(newComment)
                        commentAdapter.notifyItemInserted(comments.size - 1)
                        rvComments.scrollToPosition(comments.size - 1)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Ошибка при отправке комментария", Toast.LENGTH_SHORT).show()
                        Log.e(TAG, "Comment send failed", e)
                    }
            }
        }

        // 5) Кнопки модерации (для админа)
        val builder = AlertDialog.Builder(this).setView(view)
        if (isAdminUser && post.status == PostStatus.PENDING) {
            builder.setPositiveButton("Одобрить", null)
        }
        if (isAdminUser) {
            builder.setNegativeButton("Удалить") { _, _ -> deletePost(post) }
        }
        builder.setNeutralButton("Закрыть", null)

        val dialog = builder.create().apply { show() }

        // 6) Обработка "Одобрить"
        if (isAdminUser && post.status == PostStatus.PENDING) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setOnClickListener {
                    postsCollection.document(post.id)
                        .update("status", PostStatus.APPROVED.name)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Пост одобрен", Toast.LENGTH_SHORT).show()
                            post.status = PostStatus.APPROVED
                            loadPostsFromFirestore()
                            dialog.dismiss()
                        }
                }
        }
    }


    private fun deletePost(post: Post) {
        postsCollection.document(post.id)
            .collection("comments").get().addOnSuccessListener { snap ->
                snap.documents.forEach { it.reference.delete() }
                postsCollection.document(post.id).delete().addOnSuccessListener {
                    Toast.makeText(this, "Пост удалён", Toast.LENGTH_SHORT).show()
                    loadPostsFromFirestore()
                }
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getLastLocationAndLoadPosts()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_logout -> { showLogoutConfirmation(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Выход из аккаунта")
            .setMessage("Вы действительно хотите выйти?")
            .setPositiveButton("Да") { dialog, _ ->
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, SignInActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                dialog.dismiss()
                finish()
            }
            .setNegativeButton("Нет", null)
            .show()
    }

    private fun drawUserRadius() {
        val center = userLocation ?: return

        // Если уже был круг, удаляем его
        radiusCircle?.let { binding.mapView.overlays.remove(it) }

        // Генерируем список точек круга радиусом RADIUS_KM километров
        val circlePoints = Polygon.pointsAsCircle(center, RADIUS_KM * 1000.0)

        // Создаём Polygon и настраиваем его
        radiusCircle = Polygon().apply {
            points = circlePoints
            fillColor = Color.argb(50, 0, 0, 255)   // полупрозрачная заливка
            strokeColor = Color.BLUE               // цвет обводки
            strokeWidth = 2f                       // ширина обводки
        }

        // Добавляем круг на карту и перерисовываем
        binding.mapView.overlays.add(radiusCircle)
        binding.mapView.invalidate()
    }


}

