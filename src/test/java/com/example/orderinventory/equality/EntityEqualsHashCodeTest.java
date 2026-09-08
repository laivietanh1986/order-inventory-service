package com.example.orderinventory.equality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minh hoa vi sao equals/hashCode dua tren @GeneratedValue id pha vo hop dong
 * cua HashSet, va hai cach thay the: dung toan bo field nghiep vu, hoac dung
 * business key bat bien gan o client.
 * Chay: mvn test -Dtest=EntityEqualsHashCodeTest
 */
@DataJpaTest
class EntityEqualsHashCodeTest {

    @Autowired
    private TestEntityManager entityManager;

    // ---------- Cach 1 (SAI): equals/hashCode dua tren id ----------

    @Test
    void id_based_equals_lam_mat_item_trong_hashset_sau_khi_persist() {
        IdEqualityItem item = new IdEqualityItem("SKU-500", 3);

        Set<IdEqualityItem> set = new HashSet<>();
        set.add(item); // id dang null -> hashCode duoc tinh va "chot" vao bucket ung voi id=null

        assertThat(set.contains(item)).isTrue(); // truoc persist: van dung

        entityManager.persist(item);
        entityManager.flush(); // id duoc DB sinh ra -> hashCode() tinh lai bay gio se KHAC luc insert

        // item van la CHINH object do (cung reference), nhung HashSet da dat no
        // vao bucket cu (theo hashCode luc id=null). contains() goi hashCode()
        // MOI de tim bucket, tro toi cho khac -> "khong tim thay" chinh no.
        assertThat(set.contains(item)).isFalse();
    }

    @Test
    void id_based_equals_van_dung_khi_so_sanh_hai_instance_khac_nhau_sau_khi_da_co_id() {
        IdEqualityItem item = new IdEqualityItem("SKU-501", 1);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear(); // item bay gio la DETACHED

        IdEqualityItem reloaded = entityManager.find(IdEqualityItem.class, item.getId()); // MANAGED, object KHAC

        assertThat(reloaded).isNotSameAs(item); // hai reference khac nhau trong bo nho
        assertThat(reloaded).isEqualTo(item);   // nhung cung id -> equals() nhan ra la "cung mot dong"
    }

    // ---------- Cach 2 (tam on): equals/hashCode dua tren toan bo field nghiep vu ----------

    @Test
    void all_fields_equals_giu_duoc_item_trong_hashset_qua_luc_persist() {
        AllFieldsEqualityItem item = new AllFieldsEqualityItem("SKU-600", 5);

        Set<AllFieldsEqualityItem> set = new HashSet<>();
        set.add(item);

        entityManager.persist(item);
        entityManager.flush(); // id thay doi, nhung hashCode KHONG phu thuoc id

        assertThat(set.contains(item)).isTrue(); // van tim thay vi productSku/quantity khong doi
    }

    @Test
    void all_fields_equals_van_vo_neu_mot_field_dung_trong_equals_bi_sua_sau_khi_them_vao_set() {
        AllFieldsEqualityItem item = new AllFieldsEqualityItem("SKU-601", 1);

        Set<AllFieldsEqualityItem> set = new HashSet<>();
        set.add(item);

        item.setQuantity(99); // quantity nam trong equals/hashCode -> hashCode doi NGAY CA KHI CHUA persist

        // Loi giong het truong hop id, chi khac nguyen nhan: bat ky field
        // MUTABLE nao dung trong equals/hashCode deu nguy hiem mot khi object
        // da nam trong mot collection dua tren hash.
        assertThat(set.contains(item)).isFalse();
    }

    // ---------- Cach 3 (dung): business key/UUID gan o client, bat bien ----------

    @Test
    void business_key_giu_on_dinh_qua_ca_luc_chua_persist_lan_da_persist_lan_sua_field_khac() {
        BusinessKeyEqualityItem item = new BusinessKeyEqualityItem("SKU-700", 2);

        Set<BusinessKeyEqualityItem> set = new HashSet<>();
        set.add(item);

        assertThat(set.contains(item)).isTrue();

        entityManager.persist(item);
        entityManager.flush();

        // id thay doi tu null sang gia tri that, nhung businessKey (field DUY
        // NHAT dung cho equals/hashCode) khong doi -> hashCode on dinh.
        assertThat(set.contains(item)).isTrue();

        item.setQuantity(999); // sua field nghiep vu khac, khong nam trong equals/hashCode
        assertThat(set.contains(item)).isTrue();
    }
}
