import com.microsoft.playwright.Playwright;

public class TestPath {
    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            System.out.println(playwright.chromium().executablePath());
        }
    }
}
