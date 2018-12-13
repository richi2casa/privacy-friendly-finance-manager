package org.secuso.privacyfriendlyfinance.domain.model;


import android.arch.persistence.room.Entity;
import android.arch.persistence.room.Ignore;
import android.arch.persistence.room.Index;

@Entity(
    tableName = "Account",
    inheritSuperIndices = true,
    indices = @Index(value = "name", unique = true)
)
public class Account extends AbstractEntity {
    private String name;

    public Account() {
    }

    @Ignore
    public Account(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
