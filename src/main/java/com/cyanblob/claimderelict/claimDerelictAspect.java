package com.cyanblob.claimderelict;

import fi.bugbyte.framework.Game;
import fi.bugbyte.framework.files.CompiledClassLoader;
import fi.bugbyte.framework.screen.ScalableIconTextButton;
import fi.bugbyte.framework.screen.StageButton;
import fi.bugbyte.framework.screen.StageButton.clickHandler;
import fi.bugbyte.gen.compiled.TextButtons2;
import fi.bugbyte.gen.compiled.TextIconButton1;
import fi.bugbyte.spacehaven.ai.TradingHelper;
import fi.bugbyte.spacehaven.gui.GUI.SelectedElements;
import fi.bugbyte.spacehaven.gui.GameLog;
import fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide;
import fi.bugbyte.spacehaven.world.Ship;
import fi.bugbyte.spacehaven.world.World;
import fi.bugbyte.spacehaven.world.Ship.ShipSettings;
import fi.bugbyte.spacehaven.world.ShipHelper.ShipState;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

@Aspect
public class claimDerelictAspect {

    private static final int DEFAULT_PRICE = 1000;
    private static final int PRICE = loadPrice();

    private static int loadPrice() {
        try {
            File jar = new File(claimDerelictAspect.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File cfg = new File(jar.getParentFile(), "config.properties");
            if (cfg.isFile()) {
                Properties p = new Properties();
                try (FileInputStream in = new FileInputStream(cfg)) {
                    p.load(in);
                }
                String v = p.getProperty("price");
                if (v != null) {
                    int parsed = Integer.parseInt(v.trim());
                    System.out.println("[ClaimDerelict] using configured price: " + parsed);
                    return parsed;
                }
            }
        } catch (Exception e) {
            System.out.println("[ClaimDerelict] failed to load config, using default: " + e);
        }
        return DEFAULT_PRICE;
    }

    private static ScalableIconTextButton purchaseButton;
    private World world = null;

    @Pointcut("call(void fi.bugbyte.spacehaven.gui.GUI.SelectedElements.addExploredDerelictShipStuff(fi.bugbyte.spacehaven.world.Ship, fi.bugbyte.spacehaven.gui.MiscStuff.ShipButtonTarget)) && within(fi.bugbyte..*)")
    public void addShipStuff() {
    }

    @After("addShipStuff()")
    public void updateGui(JoinPoint joinPoint) throws Throwable {
        try {
            Object[] args = joinPoint.getArgs();
            Ship ship = (Ship) args[0];
            fi.bugbyte.spacehaven.gui.MiscStuff.ShipButtonTarget target =
                    (fi.bugbyte.spacehaven.gui.MiscStuff.ShipButtonTarget) args[1];
            SelectedElements _this = (SelectedElements) joinPoint.getThis();

            if (world == null) {
                world = ship.getWorld();
            }

            if (!(ship.isDerelict() && !ship.isUnexplored() && !ship.isPlayerShip())) {
                return;
            }

            Field claimField = _this.getClass().getDeclaredField("claimShipButton");
            claimField.setAccessible(true);
            ScalableIconTextButton claimShipButton = (ScalableIconTextButton) claimField.get(_this);
            if (claimShipButton == null) {
                Method createClaimButton = _this.getClass().getDeclaredMethod("createClaimButton");
                createClaimButton.setAccessible(true);
                createClaimButton.invoke(_this);
                claimShipButton = (ScalableIconTextButton) claimField.get(_this);
            }

            int price = PRICE;
            purchaseButton = (ScalableIconTextButton) getPurchaseButton(price);

            clickHandler originalClickHandler = claimShipButton.getClickHandler();
            purchaseButton.setClickHandler(claimDerelictClickHandler(ship, originalClickHandler, price, world));

            target.addSelectionButton((StageButton) purchaseButton);
        } catch (Exception e) {
            System.out.println("[ClaimDerelict] updateGui failed: " + e);
            e.printStackTrace();
        }
    };

    private static final int CLAIM_PADDING = 4;

    static void expandClaimableArea(Ship ship) {
        try {
            ship.setNoDismantleOrBuilding(false, false);

            short[][] plansForSize = ship.getShipHullPlansAndRestrictions();
            if (plansForSize != null && plansForSize.length > 0 && plansForSize[0].length > 0) {
                int curSizeY = plansForSize.length;
                int curSizeX = plansForSize[0].length;
                try {
                    float oldShipX = ship.getShipX();
                    float oldShipY = ship.getShipY();
                    ship.shiftInCanvas(CLAIM_PADDING, CLAIM_PADDING,
                            curSizeX + 2 * CLAIM_PADDING, curSizeY + 2 * CLAIM_PADDING);
                    com.badlogic.gdx.math.Vector2 delta =
                            fi.bugbyte.spacehaven.GridUtils.toIsometric(-CLAIM_PADDING, -CLAIM_PADDING);
                    ship.moveShip(oldShipX + delta.x, oldShipY + delta.y, true);
                    short[][] after = ship.getShipHullPlansAndRestrictions();
                    System.out.println("[ClaimDerelict] resized "
                            + curSizeY + "x" + curSizeX + " -> "
                            + after.length + "x" + after[0].length
                            + "; world delta (" + delta.x + ", " + delta.y + ")");
                } catch (Throwable ex) {
                    System.out.println("[ClaimDerelict] resize+move failed: " + ex);
                    ex.printStackTrace();
                }
            }

            short[][] plans = ship.getShipHullPlansAndRestrictions();
            if (plans == null || plans.length == 0 || plans[0].length == 0) {
                System.out.println("[ClaimDerelict] no hull grid; skipping area expansion");
                return;
            }
            int dim0 = plans.length;
            int dim1 = plans[0].length;
            int hullMask = Ship.shipHullFloorBit | Ship.shipHullWallBit | Ship.shipHullFillBit;
            int clearMask = Ship.notShipHullForbiddenBit & Ship.notShipUnclaimedBit;

            int minI = Integer.MAX_VALUE, minJ = Integer.MAX_VALUE;
            int maxI = Integer.MIN_VALUE, maxJ = Integer.MIN_VALUE;
            int hullCount = 0;
            for (int i = 0; i < dim0; i++) {
                for (int j = 0; j < dim1; j++) {
                    if ((plans[i][j] & hullMask) != 0) {
                        if (i < minI) minI = i;
                        if (j < minJ) minJ = j;
                        if (i > maxI) maxI = i;
                        if (j > maxJ) maxJ = j;
                        hullCount++;
                    }
                }
            }
            System.out.println("[ClaimDerelict] grid dims " + dim0 + "x" + dim1 + ", hull tiles: " + hullCount);
            if (minI == Integer.MAX_VALUE) {
                System.out.println("[ClaimDerelict] no hull tiles found; skipping area expansion");
                return;
            }

            int rMinI = Math.max(0, minI - CLAIM_PADDING);
            int rMinJ = Math.max(0, minJ - CLAIM_PADDING);
            int rMaxI = Math.min(dim0 - 1, maxI + CLAIM_PADDING);
            int rMaxJ = Math.min(dim1 - 1, maxJ + CLAIM_PADDING);
            System.out.println("[ClaimDerelict] hull bbox [" + minI + "," + minJ + "]-[" + maxI + "," + maxJ
                    + "]; claim rect [" + rMinI + "," + rMinJ + "]-[" + rMaxI + "," + rMaxJ + "]");

            int[][] buildRest = ship.getBuildRestrictions();
            int cellsCleared = 0;
            int buildResCleared = 0;
            for (int i = rMinI; i <= rMaxI; i++) {
                for (int j = rMinJ; j <= rMaxJ; j++) {
                    if (buildRest != null && i < buildRest.length && j < buildRest[i].length) {
                        if (buildRest[i][j] != 0) {
                            buildRest[i][j] = 0;
                            buildResCleared++;
                        }
                    }
                    short before = plans[i][j];
                    short after = (short) (before & clearMask);
                    if (after != before) {
                        plans[i][j] = after;
                        cellsCleared++;
                    }
                }
            }
            System.out.println("[ClaimDerelict] cleared forbidden/unclaimed bits on " + cellsCleared
                    + " cells; zeroed buildRestrictions on " + buildResCleared + " cells");

            com.badlogic.gdx.utils.Array<fi.bugbyte.spacehaven.world.elements.WorldObjectHelper.ClaimAreaStake> stakes =
                    ship.getUnclaimedAreas();
            int stakeCount = (stakes == null) ? 0 : stakes.size;
            System.out.println("[ClaimDerelict] pre-existing stakes: " + stakeCount);
            if (stakes != null && stakes.size > 0) {
                com.badlogic.gdx.utils.Array<fi.bugbyte.spacehaven.world.elements.WorldObjectHelper.ClaimAreaStake> copy =
                        new com.badlogic.gdx.utils.Array<>(stakes);
                int stakesCleared = 0;
                for (fi.bugbyte.spacehaven.world.elements.WorldObjectHelper.ClaimAreaStake s : copy) {
                    s.setRestrictions(ship, false);
                    ship.removeUnclaimedArea(s);
                    stakesCleared++;
                }
                System.out.println("[ClaimDerelict] removed " + stakesCleared + " stakes");
            }
        } catch (Throwable t) {
            System.out.println("[ClaimDerelict] expandClaimableArea failed: " + t);
            t.printStackTrace();
        }
    }

    TextIconButton1 getPurchaseButton(int price) {
        boolean bool = CompiledClassLoader.canCallOnGet;
        CompiledClassLoader.canCallOnGet = false;
        TextIconButton1 purchaseButton = TextButtons2.getIconBase2();
        CompiledClassLoader.canCallOnGet = bool;

        purchaseButton.setText("Purchase derelict: " + price + " credits");
        purchaseButton.toolTipText = "Allows purchasing a derelict ship";
        purchaseButton.icon = Game.library.getAnimation("claimShipButtonIcon", false);
        if (CompiledClassLoader.canCallOnGet)
            purchaseButton.onGet();

        return purchaseButton;
    }

    clickHandler claimDerelictClickHandler(Ship ship, clickHandler onClick, int price, World world)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {

        return new clickHandler() {
            public void clicked() {
                Field privateUriField;
                TradingHelper.Bank playerBank = null;

                try {
                    privateUriField = world.getClass().getDeclaredField("playerBank");
                    privateUriField.setAccessible(true);
                    playerBank = (TradingHelper.Bank) privateUriField.get(world);

                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                } catch (SecurityException e) {
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }

                try {

                    if (playerBank == null || playerBank.getCreditsAvailable() < price) {
                        GameLog.addLog("Can not afford to purchase derelict", GameLog.LogType.Failure, ship);
                        return;
                    }

                    privateUriField = ship.getClass().getDeclaredField("shipSettings");
                    privateUriField.setAccessible(true);
                    ShipSettings shipsettings;

                    try {
                        // remove the "derelict" flag
                        shipsettings = (ShipSettings) privateUriField.get(ship);
                        shipsettings.state = ShipState.Normal;

                        // easy way to set `ship.claimable = true`
                        ship.abandon(FactionSide.NotSet, false, true);

                        playerBank.addCredits(-price);

                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }

                } catch (NoSuchFieldException e) {
                    e.printStackTrace();
                } catch (SecurityException e) {
                    e.printStackTrace();
                }
                onClick.clicked();
                expandClaimableArea(ship);
            }
        };

    }
}