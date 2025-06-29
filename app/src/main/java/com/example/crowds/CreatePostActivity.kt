package com.example.crowds

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.crowds.databinding.ActivityCreatePostBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

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

        // Инициализация ViewBinding
        binding = ActivityCreatePostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация OsmDroid конфигурации
        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )

        mapView = binding.mapPickerView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)

        // Центр карты (дефолтное местоположение, например, Москва)
        val defaultPoint = GeoPoint(55.7558, 37.6173)
        mapView.controller.setCenter(defaultPoint)

        // Запрос разрешений на локацию для отображения “Мое местоположение”
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

        // Добавляем MapEventsOverlay, который перехватывает long-press
        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                // Ничего не делаем при одиночном клике
                return false
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                // При долгом нажатии ставим маркер
                setSelectedPoint(p)
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(eventsReceiver))

        // Обработка клика “Отправить”
        binding.btnSubmit.setOnClickListener {
            createPost()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationOverlay() {
        // Настраиваем провайдера и оверлей “Мое местоположение”
        val provider = GpsMyLocationProvider(this)
        val myLocationOverlay = MyLocationNewOverlay(provider, mapView)
        myLocationOverlay.enableMyLocation()
        mapView.overlays.add(myLocationOverlay)
    }

    private fun setSelectedPoint(p: GeoPoint) {
        // Удаляем предыдущие маркеры с title="Выбор"
        mapView.overlays
            .filter { it is Marker && (it as Marker).title == "Выбор" }
            .forEach { mapView.overlays.remove(it) }

        // Создаём новый маркер в выбранной точке
        val marker = Marker(mapView).apply {
            position = p
            title = "Выбор"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@CreatePostActivity, android.R.drawable.star_on)
        }
        mapView.overlays.add(marker)
        mapView.invalidate()

        selectedLocation = p
        // Показываем координаты внизу
        binding.tvSelectedCoords.text =
            "Выбрано: ${"%.5f".format(p.latitude)}, ${"%.5f".format(p.longitude)}"
    }

    private fun createPost() {
        val titleText = binding.etTitle.text.toString().trim()
        val descText = binding.etDesc.text.toString().trim()

        // Валидация полей
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

        // Получаем имя пользователя из Google-авторизации
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val userName = firebaseUser?.displayName ?: "Anonymous"

        val newPost = Post().apply {
            id          = ""
            title       = titleText
            description = descText
            latitude    = selectedLocation!!.latitude
            longitude   = selectedLocation!!.longitude
            this.userName = userName
            timestamp   = Timestamp.now()
            status      = PostStatus.PENDING
        }

        postsCollection.add(newPost)
            .addOnSuccessListener {
                finish() // Закрываем Activity после успешного добавления
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                Toast.makeText(this, "Ошибка при создании поста", Toast.LENGTH_SHORT).show()
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
