package com.artillexstudios.axtrade.trade;

import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axtrade.request.Requests;


public class TradeTicker {

    public static void start() {
        Scheduler.get().runTimer(scheduledTask -> {
            for (Trade trade : Trades.getTrades()) {
                if (trade.isEnded()) continue;
                if (!(trade.player1.hasConfirmed() && trade.player2.hasConfirmed())) continue;

                if (trade.player1.getConfirmed() == 1) {
                    trade.complete();
                    continue;
                }

                trade.player1.tick();
                trade.player2.tick();
            }

            Requests.expireRequests();
        }, 20, 20);
    }
}
