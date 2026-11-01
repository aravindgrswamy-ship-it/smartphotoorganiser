package com.example.smartgallery;

/** A group of faces that the app believes belong to the same person. */
public class PersonInfo {
    public final int id;
    public final String name;      // null until the user renames the person
    public final int photoCount;
    public final long faceId;      // a representative face, used for the avatar

    public PersonInfo(int id, String name, int photoCount, long faceId) {
        this.id = id;
        this.name = name;
        this.photoCount = photoCount;
        this.faceId = faceId;
    }

    public String label() {
        return (name == null || name.trim().isEmpty()) ? "Person " + id : name;
    }
}
