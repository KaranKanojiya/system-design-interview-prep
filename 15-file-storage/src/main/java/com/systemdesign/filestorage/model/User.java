package com.systemdesign.filestorage.model;

/**
 * User — represents a storage system user with their quota.
 *
 * Design decisions:
 * - StorageQuota is embedded: each user has exactly one quota.
 * - In a real system, users would have authentication tokens, device lists, etc.
 *   We keep it simple for the demo — focus is on storage mechanics, not auth.
 *
 * Call chain:
 *   AppConfig.createUsers → creates User with StorageQuota
 *   UploadService → UserRepository.findById(userId) → checks user.getStorageQuota().canStore()
 */
public class User {

    private final String userId;
    private final String name;
    private final String email;
    private final StorageQuota storageQuota;

    public User(String userId, String name, String email, StorageQuota storageQuota) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.storageQuota = storageQuota;
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public StorageQuota getStorageQuota() { return storageQuota; }

    @Override
    public String toString() {
        return String.format("User{id='%s', name='%s', email='%s', quota=%s}",
                userId, name, email, storageQuota);
    }
}
