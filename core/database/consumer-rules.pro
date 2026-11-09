# ProGuard / R8 consumer rules for core:database

# Room Database Rules
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep @androidx.room.Entity class * {
    *;
}
-keep @androidx.room.Dao interface * {
    *;
}
-keep class * implements androidx.room.RoomDatabase$Callback {
    *;
}
-keep class * extends androidx.room.migration.Migration {
    *;
}
-keep class * implements androidx.room.RoomDatabaseConstructor {
    *;
}
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}
-keep class * extends androidx.room.EntityDeletionOrUpdateAdapter
-keep class * extends androidx.room.EntityInsertionAdapter
-keep class * extends androidx.room.SharedSQLiteStatement
-keep class *_Impl {
    <init>(...);
    *;
}
-dontwarn androidx.room.paging.**
