package utils

import java.util.Base64
import javax.crypto.{Cipher, KeyGenerator}
import javax.inject.Inject

import play.api.Configuration

class Crypto @Inject() (configuration: Configuration) {

  private val secretKey = {
    val cryptoSecret = configuration.getString("play.crypto.secret")
    val keyGenerator = KeyGenerator.getInstance("AES")
    keyGenerator.init(128)
    keyGenerator.generateKey()
  }

  def encryptAES(plainText: String): String = {
    val plainTextBytes = plainText.getBytes
    val cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val encryptedButes = cipher.doFinal(plainTextBytes)
    Base64.getEncoder.encodeToString(encryptedButes)
  }

  def decryptAES(encryptedText: String): String = {
    val encryptedTextBytes = Base64.getDecoder.decode(encryptedText)
    val cipher = Cipher.getInstance("AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey)
    val decryptedBytes = cipher.doFinal(encryptedTextBytes)
    new String(decryptedBytes)
  }

}
