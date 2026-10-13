# The body of one seeded payment. Called by seed.sh with --argjson amount, --arg currency, --arg msisdn, --arg note.
# The variable is not called $label: that is a keyword in jq 1.6.
{operation:"COLLECT", amount:$amount, currency:$currency, country:"cm", counterpartyMsisdn:$msisdn,
 payerMessage:$note, payeeNote:$note}
