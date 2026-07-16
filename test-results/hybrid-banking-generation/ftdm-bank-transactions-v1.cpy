       01 FTDM-BANK-TXN-RECORD.
          05 TXN-ID                 PIC X(18).
          05 PRODUCT-CODE           PIC X(12).
          05 PRODUCT-ACCOUNT-ID     PIC 9(6).
          05 TXN-DATE               PIC X(10).
          05 TXN-TYPE               PIC X(16).
          05 AMOUNT                 PIC S9(9)V99 COMP-3.
          05 CURRENCY-CODE          PIC X(3).
          05 CHANNEL-CODE           PIC X(10).
          05 MCC-CODE               PIC 9(4).
          05 STATUS-CODE            PIC X(10).
          05 DESCRIPTION            PIC X(24).