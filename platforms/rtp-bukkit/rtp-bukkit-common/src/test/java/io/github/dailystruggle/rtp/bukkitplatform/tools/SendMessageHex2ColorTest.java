package io.github.dailystruggle.rtp.bukkitplatform.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Random;
import net.md_5.bungee.api.ChatColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins {@link SendMessage#Hex2Color}: every {@code #hhhhhh} / {@code &#hhhhhh} becomes a full code. */
class SendMessageHex2ColorTest {

  private static final String ABCDEF = "§x§a§b§c§d§e§f";
  private static final String C123456 = "§x§1§2§3§4§5§6";

  @Test
  @DisplayName("bare and &-prefixed hex codes both become full codes, in any order")
  void fixedInputs() {
    assertEquals("", SendMessage.Hex2Color(null));
    assertEquals("", SendMessage.Hex2Color(""));
    assertEquals("no codes here", SendMessage.Hex2Color("no codes here"));
    assertEquals("#12345 too short", SendMessage.Hex2Color("#12345 too short"));
    assertEquals(ABCDEF + "hello", SendMessage.Hex2Color("#ABCDEFhello"));
    assertEquals(ABCDEF + " x", SendMessage.Hex2Color("&#abcdef x"));
    // Repeating a code first bare, then with '&', must not leave a stray '&'.
    assertEquals(ABCDEF + " a " + ABCDEF + " b", SendMessage.Hex2Color("#abcdef a &#abcdef b"));
    assertEquals(ABCDEF + " a " + ABCDEF + " b " + ABCDEF,
        SendMessage.Hex2Color("&#abcdef a #abcdef b &#abcdef"));
    assertEquals(C123456 + " §x§6§5§4§3§2§1 " + C123456 + " " + C123456,
        SendMessage.Hex2Color("#123456 #654321 &#123456 #123456"));
    assertEquals(ABCDEF + " " + ABCDEF + " " + ABCDEF + " " + ABCDEF,
        SendMessage.Hex2Color("#AbCdEf &#AbCdEf #abcdef &#abcdef"));
    assertEquals(ABCDEF + "g1234567", SendMessage.Hex2Color("#abcdefg1234567"));
    // Only one '&' belongs to the code; a doubled '&' keeps the literal one.
    assertEquals("#" + ABCDEF + "&" + ABCDEF, SendMessage.Hex2Color("##abcdef&&#abcdef"));
    assertEquals("§a plain " + C123456 + " §x§f§e§d§c§b§a",
        SendMessage.Hex2Color("§a plain " + C123456 + " #fedcba"));
  }

  @Test
  @DisplayName("format pipeline: six legacy codes stay legacy; repeated hex never leaks '&'")
  void formatPipeline() {
    assertEquals("§a§b§c§d§e§f text",
        SendMessage.Hex2Color(ChatColor.translateAlternateColorCodes('&', "&a&b&c&d&e&f text")));
    assertEquals("§c" + ABCDEF + "x " + ABCDEF + "y",
        SendMessage.Hex2Color(ChatColor.translateAlternateColorCodes('&', "&c#abcdefx &#abcdefy")));
  }

  @Test
  @DisplayName("seeded random inputs: no hex token survives and every &# code is consumed")
  void randomInputs() {
    char[] alphabet = {'#', '&', 'a', 'F', '1', '9', 'x', ' ', 'g'};
    Random random = new Random(42L);
    for (int n = 0; n < 20_000; n++) {
      StringBuilder sb = new StringBuilder();
      int len = random.nextInt(40);
      for (int i = 0; i < len; i++) sb.append(alphabet[random.nextInt(alphabet.length)]);
      String in = sb.toString();
      String out = SendMessage.Hex2Color(in);
      assertFalse(out.matches("(?s).*#[0-9a-fA-F]{6}.*"), "hex survived: " + in + " -> " + out);
      assertEquals(SendMessage.Hex2Color(out), out, "not idempotent: " + in);
      assertEquals(in.replace("&", "").length() - countCodes(in) * 7 + countCodes(in) * 14,
          out.replace("&", "").length(), "length: " + in);
    }
  }

  /** Non-overlapping left-to-right count of {@code #hhhhhh} tokens. */
  private static int countCodes(String s) {
    int count = 0;
    for (int i = 0; i + 7 <= s.length(); ) {
      if (s.charAt(i) == '#' && isHex(s, i + 1)) {
        count++;
        i += 7;
      } else {
        i++;
      }
    }
    return count;
  }

  private static boolean isHex(String s, int from) {
    for (int i = from; i < from + 6; i++) {
      if (Character.digit(s.charAt(i), 16) < 0) return false;
    }
    return true;
  }
}
