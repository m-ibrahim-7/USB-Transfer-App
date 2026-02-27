private fun sendToPC(content: String) {
    thread {
        try {
            // APPLY REPLACEMENTS HERE
            // Case-insensitive replacement of "ejection fraction" with "EF"
            var modifiedText = content.replace("ejection fraction", "EF", ignoreCase = true)
            
            // You can add more shorthand here later, e.g.:
            // modifiedText = modifiedText.replace("Intraocular Pressure", "IOP", ignoreCase = true)

            val socket = Socket("127.0.0.1", 38300)
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(modifiedText)
            socket.close()

            runOnUiThread {
                Toast.makeText(this, "Sent: $modifiedText", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Connection Failed", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}