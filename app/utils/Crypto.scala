/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.util.Base64

import javax.crypto.{Cipher, KeyGenerator}
import javax.inject.Inject
import play.api.Configuration

class Crypto @Inject() (configuration: Configuration) {

  private val secretKey = {
    val cryptoSecret = configuration.get[String]("play.http.secret.key")
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
