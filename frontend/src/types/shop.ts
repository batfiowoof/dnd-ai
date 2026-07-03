import type { ItemKind } from "./player";
import type { ShopType } from "./world";

/** One item for sale, with its economy-adjusted unit buy price. {@code quantity} -1 = unlimited. */
export interface ShopStockView {
  srdIndex: string | null;
  name: string;
  kind: ItemKind;
  unitPriceCopper: number;
  quantity: number;
}

/** One of the player's items this shop buys back, with the unit price offered and how many they hold. */
export interface SellOffer {
  name: string;
  kind: ItemKind;
  held: number;
  unitPriceCopper: number;
}

/** A shop open at the party's current location, priced for the viewing player. Mirrors backend ShopView. */
export interface ShopView {
  key: string;
  name: string;
  type: ShopType;
  description: string;
  region: string;
  subregion: string | null;
  economyFactor: number;
  ownerNpcName: string | null;
  stock: ShopStockView[];
  sellOffers: SellOffer[];
}

/** The player's purse plus the shops open where they stand. Mirrors backend AvailableShopsDto. */
export interface AvailableShopsDto {
  copper: number;
  shops: ShopView[];
}
