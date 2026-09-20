package com.endralink.app
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class MainActivity : AppCompatActivity() {
 private lateinit var status:TextView; private lateinit var progress:ProgressBar; private lateinit var progressText:TextView
 private var selectedFile:Uri?=null; private val pickFile=1001
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  status=findViewById(R.id.status);progress=findViewById(R.id.progress);progressText=findViewById(R.id.progressText);createChannel();requestNotifications()
  findViewById<Button>(R.id.openPhone).setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},pickFile)}
  findViewById<Button>(R.id.connect).setOnClickListener{findCalculator()}
  findViewById<Button>(R.id.copy).setOnClickListener{if(selectedFile==null)status.text="Choose a file first" else{progress.visibility=View.VISIBLE;progress.progress=0;progressText.text="Ready to transfer • 0%";status.text="fx-CG50 transfer engine is being implemented";notifyProgress(0,"Ready to transfer")}}
  findViewById<Button>(R.id.eject).setOnClickListener{progress.visibility=View.GONE;progressText.text="";status.text="USB session released. Safe to disconnect after the fx-CG50 confirms."}
 }
 private fun requestNotifications(){if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),1002)}
 private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("transfers","File transfers",NotificationManager.IMPORTANCE_LOW))}
 private fun notifyProgress(p:Int,msg:String){if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
  getSystemService(NotificationManager::class.java).notify(50,NotificationCompat.Builder(this,"transfers").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("EndraLink • fx-CG50").setContentText(msg).setOnlyAlertOnce(true).setProgress(100,p,false).build())}
 private fun findCalculator(){val m=getSystemService(Context.USB_SERVICE) as UsbManager;val d:Collection<UsbDevice> = m.deviceList.values;status.text=if(d.isEmpty())"No USB device detected" else "USB device detected: "+d.first().deviceName}
 override fun onActivityResult(r:Int,c:Int,data:Intent?){super.onActivityResult(r,c,data);if(r==pickFile&&c==Activity.RESULT_OK){selectedFile=data?.data;status.text="File selected"}}
}
