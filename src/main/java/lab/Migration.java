package lab;

import java.util.List;

public record Migration(int version, String description, List<String> statements) {
}
