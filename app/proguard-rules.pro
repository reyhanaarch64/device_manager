# bouncycastle dan adb-kt dipakai lewat reflection/service loader di beberapa jalur
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**

# pertahankan info baris untuk stack trace di laporan crash
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
