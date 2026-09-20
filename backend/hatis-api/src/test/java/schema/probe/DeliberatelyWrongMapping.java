package schema.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "plat_outbox")
public class DeliberatelyWrongMapping {
    @Id
    @Column(name = "id")
    UUID id;

    @Column(name = "column_that_does_not_exist")
    String notAColumn;
}
