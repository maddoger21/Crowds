package com.example.crowds

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.Spinner
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.views.overlay.Polygon

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var isAdminUser = false

    private var filterRadiusKm = 5.0
    private var selectedCategoryFilter: PostCategory? = null
    private var adminPostFilter: AdminPostFilter = AdminPostFilter.ALL
    private var radiusCircle: Polygon? = null


    // храним все посты из Firestore
    private var lastPosts = listOf<Post>()

    companion object {
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 1
    }

    private enum class AdminPostFilter(val displayName: String) {
        ALL("Все посты"),
        NEED_REVIEW("Требуют модерации"),
        EXISTING("Существующие")
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
        binding.toolbarMain.setNavigationIcon(android.R.drawable.ic_media_previous)
        binding.toolbarMain.setNavigationContentDescription(R.string.back)
        binding.toolbarMain.setNavigationOnClickListener { finish() }

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
                setupAdminFilterSpinner()

                // --- Синхронизируем профиль в /users/{uid} ---
                FirebaseAuth.getInstance().currentUser?.let { user ->
                    syncUserProfile(user.uid, user.email.orEmpty(), user.displayName ?: "Anonymous")
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

        // === Настраиваем ползунок ===
        val ll = binding.root.findViewById<LinearLayout>(R.id.ll_radius_control)
        val tvLabel = ll.findViewById<TextView>(R.id.tv_radius_label)
        val sb = ll.findViewById<SeekBar>(R.id.seekbar_radius)

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // не ниже 1 км
                filterRadiusKm = maxOf(1.0, progress.toDouble())
                tvLabel.text = "Радиус: ${filterRadiusKm.toInt()} км"
                drawUserRadius()
                //loadPostsFromFirestore() // или заново прогнать ваш listener
                refreshMarkers()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        setupCategoryFilterSpinner()

    }

    private fun setupCategoryFilterSpinner() {
        val categories = listOf("\u0412\u0441\u0435 \u043a\u0430\u0442\u0435\u0433\u043e\u0440\u0438\u0438") +
                PostCategory.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategoryFilter.adapter = adapter
        binding.spinnerCategoryFilter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedCategoryFilter =
                        if (position == 0) null else PostCategory.fromPosition(position - 1)
                    refreshMarkers()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectedCategoryFilter = null
                    refreshMarkers()
                }
            }
    }

    private fun setupAdminFilterSpinner() {
        binding.tvAdminFilterLabel.visibility = if (isAdminUser) View.VISIBLE else View.GONE
        binding.spinnerAdminFilter.visibility = if (isAdminUser) View.VISIBLE else View.GONE
        if (!isAdminUser) return

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            AdminPostFilter.entries.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAdminFilter.adapter = adapter
        binding.spinnerAdminFilter.setSelection(adminPostFilter.ordinal)
        binding.spinnerAdminFilter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    adminPostFilter = AdminPostFilter.entries.getOrElse(position) { AdminPostFilter.ALL }
                    refreshMarkers()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    adminPostFilter = AdminPostFilter.ALL
                    refreshMarkers()
                }
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
        // Получаем и рисуем текущее местоположение + круг
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                userLocation = GeoPoint(loc.latitude, loc.longitude)
                binding.mapView.controller.setCenter(userLocation)
                addUserMarker()
                drawUserRadius()
                //loadPostsFromFirestore()
            } else {
                requestNewLocationData()
            }
        }

        postsListener = postsCollection
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener

                // сохраняем все посты
                lastPosts = snap.documents
                    .mapNotNull { it.toObject(Post::class.java)?.apply { id = it.id } }

                // и перерисовываем
                refreshMarkers()
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
    private fun syncUserProfile(uid: String, email: String, displayName: String) {
        val userDoc = firestore.collection("users").document(uid)
        userDoc.get().addOnSuccessListener { snap ->
            val existing = snap.toObject(UserReputation::class.java)
            userDoc.set(
                mapOf(
                    "uid" to uid,
                    "email" to email,
                    "displayName" to displayName,
                    "name" to displayName,
                    "role" to if (isAdminUser) "ADMIN" else "USER",
                    "reputationScore" to (existing?.reputationScore ?: 5.0),
                    "postsCreated" to (existing?.postsCreated ?: 0),
                    "confirmationsMade" to (existing?.confirmationsMade ?: 0),
                    "reportsMade" to (existing?.reportsMade ?: 0)
                ),
                SetOptions.merge()
            )
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            userLocation = GeoPoint(loc.latitude, loc.longitude)
            binding.mapView.controller.setCenter(userLocation)
            drawUserRadius()
            //loadPostsFromFirestore()
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

//    private fun loadPostsFromFirestore() {
//        if (isAdminUser) {
//            postsCollection.orderBy("timestamp", Query.Direction.DESCENDING)
//                .get().addOnSuccessListener { snap ->
//                    binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }
//                    snap.documents.mapNotNull { doc ->
//                        doc.toObject(Post::class.java)?.apply { id = doc.id }
//                    }.forEach { addPostMarker(it) }
//                    binding.mapView.invalidate()
//                }
//        } else {
//            postsCollection
//                .whereEqualTo("status", PostStatus.APPROVED.name)
//                .orderBy("timestamp", Query.Direction.DESCENDING)
//                .get()
//                .addOnSuccessListener { snap ->
//                    binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }
//                    snap.documents
//                        .mapNotNull { it.toObject(Post::class.java)?.apply { id = it.id } }
//                        .filter { post ->
//                            userLocation?.let { u ->
//                                Utils.distanceKm(
//                                    u.latitude, u.longitude,
//                                    post.latitude, post.longitude
//                                ) <= filterRadiusKm
//                            } ?: false
//                        }
//                        .forEach { addPostMarker(it) }
//                    binding.mapView.invalidate()
//                }
//        }
//    }

    private fun addPostMarker(post: Post) {
        val category = post.categoryInfo()
        Marker(binding.mapView).apply {
            position = GeoPoint(post.latitude, post.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = post.title
            snippet = "${category.displayName}: ${post.description}"
            icon = getMarkerIcon(category)
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
        updatePostTrustUi(view, post)
        view.findViewById<TextView>(R.id.tv_post_meta).text = buildPostMeta(post)
        view.findViewById<TextView>(R.id.tv_post_category).text =
            "\u041a\u0430\u0442\u0435\u0433\u043e\u0440\u0438\u044f: ${post.categoryInfo().displayName}"
        val currentUser = FirebaseAuth.getInstance().currentUser
        val currentUserUid = currentUser?.uid.orEmpty()
        val canManageOwnPost = post.authorUid.isNotBlank() && post.authorUid == currentUserUid
        val canDeletePost = isAdminUser || canManageOwnPost
        val isOwnPost = post.authorUid.isNotBlank() && post.authorUid == currentUserUid

        val btnConfirm = view.findViewById<Button>(R.id.btn_confirm_post)
        val btnReport = view.findViewById<Button>(R.id.btn_report_post)
        btnConfirm.isEnabled = !isOwnPost
        btnReport.isEnabled = !isOwnPost
        if (isOwnPost) {
            btnConfirm.text = "Автор поста"
            btnReport.text = "Автор поста"
        } else {
            checkUserFeedbackState(post.id, currentUserUid, btnConfirm, btnReport)
        }
        btnConfirm.setOnClickListener { confirmPost(post, view, btnConfirm, btnReport) }
        btnReport.setOnClickListener { showReportReasonDialog(post, view, btnConfirm, btnReport) }

        view.findViewById<Button>(R.id.btn_edit_post).apply {
            visibility = if (canManageOwnPost) View.VISIBLE else View.GONE
            setOnClickListener {
                showEditPostDialog(post, view)
            }
        }
        view.findViewById<Button>(R.id.btn_reject_post).visibility =
            if (isAdminUser && post.status == PostStatus.PENDING) View.VISIBLE else View.GONE

        val rvComments = view.findViewById<RecyclerView>(R.id.rv_comments)
        rvComments.layoutManager = LinearLayoutManager(this)

        // 1) Список комментариев
        val comments = mutableListOf<Comment>()

        // 2) Объявляем адаптер заранее, но инициализируем чуть ниже
        lateinit var commentAdapter: CommentAdapter

        // 2) Адаптер объявляем заранее
        commentAdapter = CommentAdapter(comments, isAdminUser, currentUserUid) { commentToDelete ->
            // Защита от пустого id
            if (commentToDelete.id.isBlank()) {
                Toast.makeText(this, "Нельзя удалить несохранённый комментарий", Toast.LENGTH_SHORT).show()
                return@CommentAdapter
            }
            if (!canDeleteComment(commentToDelete, currentUserUid)) {
                Toast.makeText(this, "Нет прав на удаление комментария", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, firestoreErrorMessage("Ошибка удаления", e), Toast.LENGTH_LONG).show()
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
                val user = FirebaseAuth.getInstance().currentUser
                val name = user?.displayName ?: "Anonymous"
                val newComment = Comment(
                    authorUid = user?.uid.orEmpty(),
                    authorEmail = user?.email.orEmpty(),
                    userName = name,
                    text = text,
                    timestamp = Timestamp.now()
                )
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
        if (canDeletePost) {
            builder.setNegativeButton("Удалить") { _, _ -> deletePost(post) }
        }
        if (isAdminUser) {
            builder.setNeutralButton("Архивировать", null)
        } else {
            builder.setNeutralButton("Закрыть", null)
        }

        val dialog = builder.create().apply { show() }
        view.findViewById<Button>(R.id.btn_close_post_details).setOnClickListener {
            dialog.dismiss()
        }

        if (isAdminUser) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                ?.setOnClickListener {
                    updatePostStatus(post, PostStatus.ARCHIVED) {
                        Toast.makeText(this, "Пост архивирован", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
        }

        view.findViewById<Button>(R.id.btn_reject_post).setOnClickListener {
            updatePostStatus(post, PostStatus.REJECTED) {
                Toast.makeText(this, "Пост отклонён", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        // 6) Обработка "Одобрить"
        if (isAdminUser && post.status == PostStatus.PENDING) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setOnClickListener {
                    updatePostStatus(post, PostStatus.APPROVED) {
                        Toast.makeText(this, "Пост одобрен", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
        }
    }

    private fun canDeleteComment(comment: Comment, currentUserUid: String): Boolean =
        isAdminUser || (comment.authorUid.isNotBlank() && comment.authorUid == currentUserUid)

    private fun updatePostTrustUi(view: View, post: Post) {
        view.findViewById<TextView>(R.id.tv_post_trust).text =
            "Достоверность: ${"%.1f".format(post.trustScore)} (${post.trustLevel.ifBlank { "не рассчитана" }})"
        view.findViewById<TextView>(R.id.tv_post_duplicates).apply {
            visibility = if (post.duplicateCount > 0) View.VISIBLE else View.GONE
            text = "Возможные дубли: ${post.duplicateCount}"
        }
    }

    private fun buildPostMeta(post: Post): String {
        val createdAt = post.timestamp?.toDate()?.let {
            android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", it)
        } ?: "неизвестно"
        return "Автор: ${post.userName}\n" +
                "Дата: $createdAt\n" +
                "Статус: ${post.status.name}\n" +
                "Подтверждений: ${post.confirmationsCount}; жалоб: ${post.reportsCount}"
    }

    private fun checkUserFeedbackState(
        postId: String,
        currentUserUid: String,
        btnConfirm: Button,
        btnReport: Button
    ) {
        if (currentUserUid.isBlank()) {
            btnConfirm.isEnabled = false
            btnReport.isEnabled = false
            return
        }
        postsCollection.document(postId)
            .collection("confirmations")
            .document(currentUserUid)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    btnConfirm.isEnabled = false
                    btnConfirm.text = "Вы подтвердили"
                }
            }
        postsCollection.document(postId)
            .collection("reports")
            .document(currentUserUid)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    btnReport.isEnabled = false
                    btnReport.text = "Жалоба отправлена"
                }
            }
    }

    private fun confirmPost(post: Post, view: View, btnConfirm: Button, btnReport: Button) {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid.orEmpty()
        if (uid.isBlank()) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        if (post.authorUid == uid) {
            Toast.makeText(this, "Нельзя подтверждать свой пост", Toast.LENGTH_SHORT).show()
            return
        }

        val postRef = postsCollection.document(post.id)
        val confirmationRef = postRef.collection("confirmations").document(uid)
        val userRef = firestore.collection("users").document(uid)
        firestore.runTransaction { tx ->
            if (tx.get(confirmationRef).exists()) throw IllegalStateException("already_confirmed")

            val current = tx.get(postRef).toObject(Post::class.java) ?: post
            val confirmations = current.confirmationsCount + 1
            val trust = calculateTrustForPost(
                current.copy(confirmationsCount = confirmations),
                5.0
            )
            tx.set(
                confirmationRef,
                PostConfirmation(uid, user?.email.orEmpty(), user?.displayName ?: "Anonymous", Timestamp.now())
            )
            tx.update(
                postRef,
                mapOf(
                    "confirmationsCount" to confirmations,
                    "trustScore" to trust.score,
                    "trustLevel" to trust.level,
                    "needsAdminReview" to shouldNeedAdminReview(current.reportsCount, trust.score)
                )
            )
            trust
        }.addOnSuccessListener { trust ->
            incrementUserCounterBestEffort(
                userRef,
                user?.email.orEmpty(),
                user?.displayName ?: "Anonymous",
                "confirmationsMade"
            )
            post.confirmationsCount += 1
            post.trustScore = trust.score
            post.trustLevel = trust.level
            post.needsAdminReview = shouldNeedAdminReview(post.reportsCount, trust.score)
            updatePostTrustUi(view, post)
            view.findViewById<TextView>(R.id.tv_post_meta).text = buildPostMeta(post)
            btnConfirm.isEnabled = false
            btnConfirm.text = "Вы подтвердили"
            btnReport.isEnabled = true
            Toast.makeText(this, "Подтверждение учтено", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            val msg = if (e is IllegalStateException && e.message == "already_confirmed") {
                "Вы уже подтверждали этот пост"
            } else {
                firestoreErrorMessage("Ошибка подтверждения", e)
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Confirm post failed", e)
        }
    }

    private fun showReportReasonDialog(post: Post, view: View, btnConfirm: Button, btnReport: Button) {
        val reasons = arrayOf(
            "Недостоверная информация",
            "Дубликат",
            "Спам",
            "Опасный/оскорбительный контент",
            "Другое"
        )
        AlertDialog.Builder(this)
            .setTitle("Причина жалобы")
            .setItems(reasons) { _, which ->
                reportPost(post, reasons[which], view, btnConfirm, btnReport)
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun reportPost(
        post: Post,
        reason: String,
        view: View,
        btnConfirm: Button,
        btnReport: Button
    ) {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid.orEmpty()
        if (uid.isBlank()) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        if (post.authorUid == uid) {
            Toast.makeText(this, "Нельзя жаловаться на свой пост", Toast.LENGTH_SHORT).show()
            return
        }

        val postRef = postsCollection.document(post.id)
        val reportRef = postRef.collection("reports").document(uid)
        val userRef = firestore.collection("users").document(uid)
        firestore.runTransaction { tx ->
            if (tx.get(reportRef).exists()) throw IllegalStateException("already_reported")

            val current = tx.get(postRef).toObject(Post::class.java) ?: post
            val reports = current.reportsCount + 1
            val trust = calculateTrustForPost(
                current.copy(reportsCount = reports),
                5.0
            )
            tx.set(
                reportRef,
                PostReport(uid, user?.email.orEmpty(), user?.displayName ?: "Anonymous", reason, Timestamp.now())
            )
            tx.update(
                postRef,
                mapOf(
                    "reportsCount" to reports,
                    "trustScore" to trust.score,
                    "trustLevel" to trust.level,
                    "needsAdminReview" to shouldNeedAdminReview(reports, trust.score)
                )
            )
            trust
        }.addOnSuccessListener { trust ->
            incrementUserCounterBestEffort(
                userRef,
                user?.email.orEmpty(),
                user?.displayName ?: "Anonymous",
                "reportsMade"
            )
            post.reportsCount += 1
            post.trustScore = trust.score
            post.trustLevel = trust.level
            post.needsAdminReview = shouldNeedAdminReview(post.reportsCount, trust.score)
            updatePostTrustUi(view, post)
            view.findViewById<TextView>(R.id.tv_post_meta).text = buildPostMeta(post)
            btnReport.isEnabled = false
            btnReport.text = "Жалоба отправлена"
            btnConfirm.isEnabled = true
            Toast.makeText(this, "Жалоба отправлена", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            val msg = if (e is IllegalStateException && e.message == "already_reported") {
                "Вы уже жаловались на этот пост"
            } else {
                firestoreErrorMessage("Ошибка отправки жалобы", e)
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, "Report post failed", e)
        }
    }

    private fun calculateTrustForPost(post: Post, authorReputation: Double = 5.0): TrustResult {
        val createdAt = post.timestamp?.toDate()?.time ?: System.currentTimeMillis()
        return TrustScoreCalculator.calculateTrustScore(
            status = post.status,
            confirmationsCount = post.confirmationsCount,
            reportsCount = post.reportsCount,
            duplicateCount = post.duplicateCount,
            authorReputation = authorReputation,
            createdAt = createdAt
        )
    }

    private fun shouldNeedAdminReview(reportsCount: Int, trustScore: Double): Boolean =
        reportsCount >= 3 || trustScore < 30.0

    private fun firestoreErrorMessage(prefix: String, error: Exception): String {
        val code = (error as? FirebaseFirestoreException)?.code
        return if (code != null) {
            "$prefix: $code"
        } else {
            "$prefix: ${error.localizedMessage ?: error.message ?: "неизвестная ошибка"}"
        }
    }

    private fun incrementUserCounterBestEffort(
        userRef: com.google.firebase.firestore.DocumentReference,
        email: String,
        displayName: String,
        counterField: String
    ) {
        userRef.set(
            mapOf(
                "email" to email,
                "displayName" to displayName,
                "reputationScore" to 5.0,
                counterField to FieldValue.increment(1)
            ),
            SetOptions.merge()
        ).addOnFailureListener { e ->
            Log.w(TAG, "User reputation counter update failed: $counterField", e)
        }
    }

    private fun updatePostStatus(post: Post, newStatus: PostStatus, onSuccess: () -> Unit) {
        if (post.authorUid.isBlank()) {
            updatePostStatusWithReputation(post, newStatus, 5.0, onSuccess)
            return
        }
        firestore.collection("users").document(post.authorUid).get()
            .addOnSuccessListener { userSnap ->
                updatePostStatusWithReputation(
                    post,
                    newStatus,
                    userSnap.getDouble("reputationScore") ?: 5.0,
                    onSuccess
                )
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Author reputation load failed", e)
                updatePostStatusWithReputation(post, newStatus, 5.0, onSuccess)
            }
    }

    private fun updatePostStatusWithReputation(
        post: Post,
        newStatus: PostStatus,
        authorReputation: Double,
        onSuccess: () -> Unit
    ) {
        val trust = calculateTrustForPost(post.copy(status = newStatus), authorReputation)
        postsCollection.document(post.id)
            .update(
                mapOf(
                    "status" to newStatus.name,
                    "trustScore" to trust.score,
                    "trustLevel" to trust.level,
                    "needsAdminReview" to shouldNeedAdminReview(post.reportsCount, trust.score)
                )
            )
            .addOnSuccessListener {
                post.status = newStatus
                post.trustScore = trust.score
                post.trustLevel = trust.level
                post.needsAdminReview = shouldNeedAdminReview(post.reportsCount, trust.score)
                refreshMarkers()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка изменения статуса", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Post status update failed", e)
            }
    }

    private fun showEditPostDialog(post: Post, detailsView: View) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etTitle = EditText(this).apply {
            hint = "Заголовок"
            setText(post.title)
        }
        val etDesc = EditText(this).apply {
            hint = "Описание"
            setText(post.description)
            minLines = 3
        }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                PostCategory.entries.map { it.displayName }
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(post.categoryInfo().ordinal)
        }

        container.addView(etTitle)
        container.addView(etDesc)
        container.addView(categorySpinner)

        AlertDialog.Builder(this)
            .setTitle("Редактирование поста")
            .setView(container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = etTitle.text.toString().trim()
                        val description = etDesc.text.toString().trim()
                        val category = PostCategory.fromPosition(categorySpinner.selectedItemPosition)
                        if (title.isEmpty() || description.isEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Заполните заголовок и описание",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }

                        postsCollection.document(post.id)
                            .update(
                                mapOf(
                                    "title" to title,
                                    "description" to description,
                                    "category" to category.name
                                )
                            )
                            .addOnSuccessListener {
                                post.title = title
                                post.description = description
                                post.category = category.name
                                detailsView.findViewById<TextView>(R.id.tv_post_title).text = title
                                detailsView.findViewById<TextView>(R.id.tv_post_desc).text = description
                                detailsView.findViewById<TextView>(R.id.tv_post_category).text =
                                    "\u041a\u0430\u0442\u0435\u0433\u043e\u0440\u0438\u044f: ${category.displayName}"
                                refreshMarkers()
                                Toast.makeText(this@MainActivity, "Пост обновлён", Toast.LENGTH_SHORT).show()
                                dismiss()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this@MainActivity, "Ошибка обновления поста", Toast.LENGTH_SHORT).show()
                                Log.e(TAG, "Post update failed", e)
                            }
                    }
                }
                show()
            }
    }


    private fun deletePost(post: Post) {
        val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val canDelete = isAdminUser || (post.authorUid.isNotBlank() && post.authorUid == currentUserUid)
        if (!canDelete) {
            Toast.makeText(this, "Нет прав на удаление поста", Toast.LENGTH_SHORT).show()
            return
        }
        postsCollection.document(post.id)
            .collection("comments").get().addOnSuccessListener { snap ->
                snap.documents.forEach { it.reference.delete() }
                postsCollection.document(post.id).delete().addOnSuccessListener {
                    Toast.makeText(this, "Пост удалён", Toast.LENGTH_SHORT).show()
                    //loadPostsFromFirestore()
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

    //общий метод перерисовки
    private fun refreshMarkers() {
        val center = userLocation
        if (center == null) return

        // фильтруем
        val postsByRole = if (isAdminUser) {
            lastPosts.filter { post ->
                when (adminPostFilter) {
                    AdminPostFilter.ALL -> true
                    AdminPostFilter.NEED_REVIEW -> needsModeratorAttention(post)
                    AdminPostFilter.EXISTING -> post.status == PostStatus.APPROVED
                }
            }
        } else {
            lastPosts.filter { post ->
                post.status == PostStatus.APPROVED &&
                        Utils.distanceKm(center.latitude, center.longitude, post.latitude, post.longitude) <= filterRadiusKm
            }
        }
        val toShow = selectedCategoryFilter?.let { category ->
            postsByRole.filter { it.categoryInfo() == category }
        } ?: postsByRole

        // убираем старые пост-маркеры
        binding.mapView.overlays.removeIf { it is Marker && it.title != "Вы здесь" }

        // добавляем новые
        toShow.forEach { addPostMarker(it) }

        binding.mapView.invalidate()
    }

    private fun needsModeratorAttention(post: Post): Boolean =
        post.status == PostStatus.PENDING ||
                post.needsAdminReview ||
                post.trustScore < 30.0 ||
                post.reportsCount >= 3

    private fun drawUserRadius() {
        val center = userLocation ?: return

        // удаляем старый круг
        radiusCircle?.let { binding.mapView.overlays.remove(it) }

        val pts = Polygon.pointsAsCircle(center, filterRadiusKm * 1000.0)
        radiusCircle = Polygon().apply {
            points = pts
            fillColor = Color.argb(50, 0, 0, 255)
            strokeColor = Color.BLUE
            strokeWidth = 2f
        }

        binding.mapView.overlays.add(0, radiusCircle)
        binding.mapView.invalidate()
    }

    private fun getMarkerIcon(category: PostCategory): Drawable? {
        val drawable = ContextCompat.getDrawable(this, category.markerIcon) ?: return null
        val wrapped = DrawableCompat.wrap(drawable).mutate()
        DrawableCompat.setTint(wrapped, category.markerColor)
        return wrapped
    }


}

