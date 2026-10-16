package com.antitheft.guardian.commands

import android.content.Context
import android.util.Log
import com.antitheft.common.crypto.EcdsaSigner
import com.antitheft.guardian.db.DirectBootStorageHelper
import java.util.Base64

data class CommandDto(
    val id: String = "",
    val protected_device_id: String = "",
    val command_type: String = "",
    val sequence_counter: Long = 0L,
    val expiry_epoch: Long = 0L,
    val parameters_json: String = "{}",
    val admin_signature: String = ""
)

sealed class CommandValidationResult {
    data class Success(val executed: Boolean) : CommandValidationResult()
    object WrongTargetDevice : CommandValidationResult()
    data class Expired(val expiryEpoch: Long, val currentEpoch: Long) : CommandValidationResult()
    data class Replayed(val incomingSeq: Long, val lastSeenSeq: Long) : CommandValidationResult()
    object MissingAdminKey : CommandValidationResult()
    object InvalidSignature : CommandValidationResult()
    data class Error(val message: String) : CommandValidationResult()
}

class CommandProcessor(
    private val context: Context,
    private val storageHelper: DirectBootStorageHelper = DirectBootStorageHelper(context)
) {

    fun processCommand(cmd: CommandDto): CommandValidationResult {
        return processCommand(
            commandId = cmd.id,
            targetDeviceId = cmd.protected_device_id,
            commandType = cmd.command_type,
            sequenceCounter = cmd.sequence_counter,
            expiryEpoch = cmd.expiry_epoch,
            parametersJson = cmd.parameters_json,
            adminSignatureBase64 = cmd.admin_signature
        )
    }

    fun processCommand(
        commandId: String,
        targetDeviceId: String,
        commandType: String,
        sequenceCounter: Long,
        expiryEpoch: Long,
        parametersJson: String,
        adminSignatureBase64: String
    ): CommandValidationResult {
        val currentDeviceId = storageHelper.getDeviceId()
        if (targetDeviceId != currentDeviceId) {
            Log.w(TAG, "Command targeted for $targetDeviceId but this device is $currentDeviceId. Rejecting.")
            return CommandValidationResult.WrongTargetDevice
        }

        val nowEpoch = System.currentTimeMillis() / 1000
        if (expiryEpoch <= nowEpoch) {
            Log.w(TAG, "Command expired: expiry $expiryEpoch <= now $nowEpoch. Rejecting.")
            return CommandValidationResult.Expired(expiryEpoch, nowEpoch)
        }

        val lastSeenSeq = storageHelper.getLastAdminCommandSequence()
        if (sequenceCounter <= lastSeenSeq) {
            Log.w(TAG, "Command sequence non-monotonic or replayed: $sequenceCounter <= $lastSeenSeq. Rejecting.")
            return CommandValidationResult.Replayed(sequenceCounter, lastSeenSeq)
        }

        val adminPem = storageHelper.getAdminPublicKeyPem()
        if (adminPem.isNullOrBlank()) {
            Log.w(TAG, "No Admin public key pinned on this Guardian phone. Cannot verify signature. Rejecting.")
            return CommandValidationResult.MissingAdminKey
        }

        return try {
            val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
                commandId = "",
                targetDeviceId = targetDeviceId,
                commandType = commandType,
                sequenceCounter = sequenceCounter,
                expiryEpoch = expiryEpoch,
                parametersJson = parametersJson
            )

            val publicKey = EcdsaSigner.parsePublicKeyFromPem(adminPem)
            val derSignature = Base64.getDecoder().decode(adminSignatureBase64.trim())

            val isValid = EcdsaSigner.verifySignature(publicKey, canonicalBytes, derSignature)
            if (!isValid) {
                Log.e(TAG, "Cryptographic signature check FAILED for Admin command! Rejecting forged command.")
                return CommandValidationResult.InvalidSignature
            }

            // Successfully verified! Update monotonic sequence counter
            storageHelper.setLastAdminCommandSequence(sequenceCounter)

            // Execute recovery action
            val executed = CommandExecutionManager.executeCommand(context, commandType, parametersJson)
            CommandValidationResult.Success(executed)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during command validation/execution", e)
            CommandValidationResult.Error(e.message ?: "Validation error")
        }
    }

    companion object {
        private const val TAG = "CommandProcessor"
    }
}
